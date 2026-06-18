# AI Probability Gate — Design (Phase 1: Shadow-mode generator + calibration)

## Problem

The signal engine's `alignment` score is an uncalibrated sum of dimension magnitudes.
Measured on 1,324 real outcomes it is flat-to-inverted vs win rate (70+ bucket has
*negative* expectancy; 40–55 wins most). The system cannot rank trades by quality, so it
fires edgeless, correlation-blind entries. We want a **calibrated win-probability** per
candidate trade, used as a go/no-go, derived from real market data — replacing `alignment`
as the entry driver.

## Decisions (locked in brainstorming)

| Decision | Choice |
|---|---|
| Estimator | **Hybrid**: calibrated stats base + LLM reasoning overlay |
| Event | **P(target before stop)** within hold window — outcome-matched, labeled by `signal_outcomes`-style forward evaluation |
| Rollout | **Shadow first**, promote to live gate only after calibration is confirmed on fresh out-of-sample data |
| Scope | **Hourly raw-data generator** scanning all tracked symbols (eventually replaces detectors) |
| Geometry | **ATR-based deterministic**: entry = current price, stop = entry ∓ k×ATR, target = entry ± R×risk |

## Hard constraint that drives the phasing

Only the 6 dimension scores exist in `signal_outcomes` history. The rich features (RSI,
Bollinger, MACD, multi-venue liquidations, funding, OI, IV, sentiment, realized vol) have
**no historical labels** — they accumulate only going forward. Therefore:

- The **rich** statistical model cannot be trained today.
- **Shadow mode is the training-data collection mechanism** — it logs the full rich feature
  vector + predicted probabilities + realized outcome for every hourly candidate.
- The **LLM works immediately** (no training); a **logistic baseline** can be trained now on
  the dimension-score features only.

## Phasing

| Phase | Scope | Live? |
|---|---|---|
| **1 (this spec)** | Hourly scan → ATR candidates → stats baseline P + LLM P (both logged) → shadow-persist candidate + rich features + predicted P → forward-evaluate synthetic outcome → calibration report | No (shadow) |
| 2 | Train rich-feature model on accrued shadow data; promote to live EV gate once calibrated | Yes (gated) |
| 3 | Retire `alignment`/detectors as entry driver; generator becomes primary entry source | Yes |

## Phase 1 architecture

Lives in **signal-service**, new package `com.cryptoradar.signal.probability`. Reuses
`DataAggregator`/`MarketContext` (dimension scores + raw inputs), `CandleClient` (candles
for ATR + forward evaluation), and the `OutcomeEvaluator` forward-walk pattern.

### Components

1. **`FeatureAssembler`** — builds a `FeatureVector` (named doubles) for a symbol at scan
   time from the existing `MarketContext` (6 dimension scores, regime, funding, OI, whale,
   long/short, realized vol, recent liquidation imbalance, news sentiment) plus indicators
   computed from candles (ATR%, RSI, Bollinger %B, MACD histogram). One clear input → output;
   no I/O of its own beyond what the caller passes in.

2. **`CandidateBuilder`** — given current price + ATR + direction, returns a `Candidate`
   with deterministic geometry: `entry = price`, `stop = entry ∓ STOP_ATR_MULT×ATR`,
   `target = entry ± TARGET_R × risk`. Enforces existing `MIN_RISK_PCT`. Pure.

3. **`WinProbabilityEstimator`** — produces two independent probabilities per candidate
   (logged separately so each is calibrated on its own; the blend is decided later, in
   Phase 2, from evidence):
   - **`LogisticWinModel`** — logistic regression on the dimension-score features. Trained
     in-process at startup (and on a schedule) by gradient descent over closed
     `signal_outcomes` rows — self-contained, no external ML tooling, honest (trains on real
     data only). Serves `P = sigmoid(w·x + b)`.
   - **`LlmProbabilityClient`** — wraps the existing `GeminiService`; prompts for
     P(target before stop) given a compact feature summary + the candidate levels; parses a
     probability + short reasoning. Fail-open (null on error; candidate still logged with
     stats P only).

4. **`ProbabilityScanScheduler`** — `@Scheduled` hourly. For each tracked symbol: assemble
   features → build LONG and SHORT candidates → estimate both probabilities for each → keep
   the higher-EV side → persist a shadow candidate. Try/catch per symbol (one bad symbol
   never breaks the scan), mirroring the existing schedulers.

5. **`probability_candidates`** table (TimescaleDB hypertable on `scanned_at`): `scanned_at,
   symbol, direction, entry_price, stop_price, target_price, atr, risk_reward, features
   JSONB, stats_prob, llm_prob, llm_reasoning, status, closed_at, closed_price,
   final_outcome` (HIT_TARGET / HIT_STOP / EXPIRED). Index `(status, scanned_at DESC)` for the
   evaluator and `(symbol, scanned_at DESC)`.

6. **`ShadowOutcomeEvaluator`** — `@Scheduled`, walks 1m candles forward for each PENDING
   candidate (reusing the `OutcomeEvaluator` walk style), sets `final_outcome` when target or
   stop is touched, or EXPIRED past the hold window. This produces the realized label.

7. **`CalibrationResource`** — `GET /api/signals/probability/calibration` returns the
   reliability curve: closed candidates grouped into probability buckets (0–10…90–100), with
   predicted-mid vs realized win-rate and sample size, for `stats_prob` and `llm_prob`
   separately. This is how calibration is judged before any promotion.

### Data flow

`hourly tick → for each symbol: FeatureAssembler → CandidateBuilder(long/short) →
WinProbabilityEstimator(stats + llm) → pick higher-EV side → persist PENDING candidate`.
Separately: `ShadowOutcomeEvaluator (scheduled) → walk candles → close candidate with
realized outcome`. On demand: `CalibrationResource → reliability curve`.

### What Phase 1 deliberately does NOT do (YAGNI / out of scope)

- No live orders, no execution change, no `alignment` removal. Pure shadow.
- No rich-feature stats model (no historical labels yet) — only the dimension-score logistic
  baseline + LLM. The rich model is Phase 2, trained on the data this phase collects.
- No blend/ensemble weighting committed — both probabilities logged independently.
- No pyramiding, no position sizing.

## Error handling

- Per-symbol try/catch in the scan; one symbol's failure is logged and skipped.
- LLM failures fail-open: candidate persisted with stats P only, `llm_prob` null.
- Forward evaluator and calibration query fail-open (log + skip), never throw on a tick.
- Logistic training fail-open: if a training run errors, keep the prior coefficients.

## Testing

- `shared-trade-core`/pure units where possible: `CandidateBuilder` geometry (stop/target/RR,
  MIN_RISK enforcement), `LogisticWinModel` (sigmoid, a fixed-coefficient prediction, one
  gradient-descent step on a tiny synthetic-but-pure dataset — numbers never become
  user-visible state, only assert the math), feature-vector assembly mapping.
- Calibration bucketing logic unit-tested (given labeled rows → expected buckets).
- Live verification: scanner produces candidates for all symbols; `feed-health`-style check
  that `probability_candidates` advances; calibration endpoint returns buckets as candidates
  close. No fabricated rows — real scans, real forward-evaluated outcomes only.

## Success criteria

1. Hourly scan persists a candidate per tracked symbol with both probabilities + features.
2. Shadow evaluator closes candidates with real forward-walked outcomes.
3. `GET /api/signals/probability/calibration` returns a reliability curve for stats and LLM.
4. Zero impact on live execution (shadow only) — verified by no new execution events.
5. Tests pass; services build; no fabricated data anywhere in the pipeline.
