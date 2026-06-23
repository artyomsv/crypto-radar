# Probability gate — parallel `v3-feature-dir` candidate generator

**Date:** 2026-06-23
**Status:** Design approved, pre-implementation
**Service:** signal-service (`com.cryptoradar.signal.probability`)
**Predecessor spec:** `2026-06-18-ai-probability-gate-design.md` (Phase 1 shadow + Phase 2 flip)

## Problem

The Phase 2 shadow config `v2-1to1-flip` has now closed ~1,080 candidates over ~4 days at a
**~50% win rate** (49.7–53.8% across reads), i.e. **EV ≈ 0 gross, negative after fees** at 1:1.
The decisive finding is not "the flip loses" — it is that the candidate **direction source**,
`sign(overallScore)`, carries **no usable directional information in either polarity**: the
original and inverted versions both land at coin-flip. Changing geometry while keeping that
direction source would almost certainly reproduce ~50%, because the broken part is the direction,
not the risk/reward shape.

We want to keep `v2-1to1-flip` running (it is the control) and add a **second shadow generator in
parallel** that changes only the direction source, to test a single clean hypothesis.

## Hypothesis

**Does trade direction derived from the raw technical features we already log beat
`sign(overallScore)`?**

The features in question (already assembled per candidate by `FeatureAssembler` →
`TechnicalIndicators` + `LiquidationImbalanceReader`):

- RSI(14)
- Bollinger %B
- MACD histogram
- momentum
- realized volatility %
- volume ratio
- 24h liquidation imbalance (multi-venue LONG-vs-SHORT $)

Everything else is held **identical to the flip** — 1:1 geometry (1.5×ATR stop, 1R target),
stats + LLM scoring, the same `ShadowOutcomeEvaluator` forward walk, the same symbols and hourly
cadence. One variable. The headline metric is the realized win rate of the feature-chosen
direction versus the flip's ~50%.

Still **shadow** — no orders, no execution change. Promotion to a live EV gate remains a later,
separate decision gated on out-of-sample evidence.

## Non-goals

- No live execution, sizing, or order placement.
- No change to the flip's behavior, tag, geometry, or accrued data (it is the control).
- No new scheduler — reuse the existing hourly scan and the existing forward evaluator.
- Not interpreting model coefficients (multicollinearity among features is acceptable for a
  direction logistic).

## Architecture (CDI generator beans)

Chosen over a `@ConfigMapping` map (awkward when one generator needs an injected model) and over a
hardcoded second block in the scheduler (entangles two experiments in one method). The bean
approach is the most isolated, most testable, and matches the codebase's CDI style; a future third
config is just another bean.

### New types

- **`CandidateGenerator`** (interface)
  - `String tag()`
  - `boolean enabled()`
  - `boolean runLlm()`
  - `Optional<Candidate> build(DirectionContext ctx)`
  - Geometry is baked into each implementing bean.

- **`DirectionContext`** (record / parameter object)
  - Carries `TradingSignal signal`, `List<CandleBar> bars`, `double atr`, `double entry`,
    `TechnicalIndicators indicators`, `Map<String,Double> dimScores`. Keeps generator method
    signatures within the 3-parameter rule.

- **`FlipGenerator`** (bean) — extraction of today's inline scheduler logic, behavior-preserving.
  - Direction = `invert(sign(overallScore))`; stop 1.5×ATR; target 1R; tag `v2-1to1-flip`;
    `runLlm = true`.
  - **Must emit byte-identical candidates to the current running v2** so the in-flight experiment
    is not disturbed. Enforced by a regression test.

- **`FeatureDirectionGenerator`** (bean) — new.
  - Direction = `DirectionModel.predictLongWins(features) >= 0.5 ? LONG : SHORT`.
  - Stop 1.5×ATR; target 1R; tag `v3-feature-dir`; `runLlm = true`.
  - Returns `Optional.empty()` (candidate honestly skipped) when the model is untrained.

- **`DirectionModel`**
  - Logistic regression predicting **P(LONG hits the 1:1 target before its stop)** from the
    7-feature vector. Direction picked = LONG if ≥ 0.5 else SHORT.
  - Reuses `LogisticWinModel` generalized to N features (it already accepts `double[][]`;
    verify width-generality during implementation and generalize if the predict path assumes 6).
  - Untrained → `isTrained() == false`; the generator skips rather than guessing.

- **`DirectionModelTrainer`**
  - `@Observes StartupEvent` (prime) + `@Scheduled` (`probability.direction-model.retrain-interval`,
    default 6h).
  - Builds the training set from **real historical 1h candles**: for each tracked symbol, walk
    historical bars; at each bar `t` with ≥ lookback bars before it, compute the feature vector from
    bars **≤ t** and the label from bars **> t** — did the LONG 1:1 trade hit target before stop
    within the hold window (default 72h), using real future candles and the same stop-first-on-
    straddle rule as `ShadowOutcomeEvaluator`. Train the logistic on all (features, label) pairs.
  - ~60–76 days × 13 symbols of 1h candles already exist in `candles`, so the model is trained from
    first boot; v3 candidates accrue from day one.
  - Strict look-ahead discipline: features never read bars at index > t.

### Modified types

- **`ProbabilityScanScheduler`**
  - Inject `Instance<CandidateGenerator>`. Per scan, per symbol (fetch candles + ATR + indicators
    once), iterate **enabled** generators: `build(ctx)` → if present, score (stats always; LLM if
    `runLlm()`) → persist with `generator.tag()`.
  - The current `configTag` / `stopAtrMult` / `targetR` / `invertDirection` fields move into
    `FlipGenerator`.
  - Existing per-candidate try/catch keeps one generator's failure from killing the others.

- **`CalibrationResource`** — optional `?tag=` query param.
  - `GET …/calibration` → defaults to `v2-1to1-flip` (unchanged; no broken consumers).
  - `GET …/calibration?tag=v3-feature-dir` → the new curve.
  - `CalibrationReporter` and the repo queries are already tag-scoped — thread the param through.
  - Gateway proxy route already matches `/signals/probability/*`; no gateway change.

### Unchanged

- **`ShadowOutcomeEvaluator`** is tag-agnostic — v3 PENDING rows get `mfe_atr` / `mae_atr` / status
  via the same forward walk. No change.
- **`probability_candidates`** schema already carries `config_tag`, `mfe_atr`, `mae_atr`,
  `calibrated_prob`. No migration.

## Data flow (per hourly scan, per symbol)

```
fetch 1h candles + ATR + TechnicalIndicators + dimScores  (once per symbol)
  └─ for each enabled CandidateGenerator:
       build(ctx) ──► Optional<Candidate>
         flip:          dir = invert(sign(overallScore)), 1:1
         feature-dir:   dir = DirectionModel.predictLongWins(features) ≥ .5 ? LONG : SHORT, 1:1
                        (empty if model untrained → skip)
       present? ──► statsProb (always)
                    llmProb   (if runLlm)
                    calibratedProb
                    featuresJson
                    persist(tag = generator.tag())
```

```
DirectionModelTrainer (startup + every 6h):
  historical 1h candles ──► for each bar t (lookback ≤ t):
       X = features(bars ≤ t)
       y = LONG-1:1 hit target before stop in (t, t+72h]   (real future candles)
   ──► train logistic  ──► DirectionModel
```

## Config (`application.properties`)

```properties
# --- generators (each a CDI bean; toggle via .enabled) ---
probability.generator.flip.enabled=true
probability.generator.feature-dir.enabled=true

# v3 geometry held identical to the flip for a clean one-variable comparison
probability.generator.feature-dir.stop-atr-mult=1.5
probability.generator.feature-dir.target-r=1.0
probability.generator.feature-dir.run-llm=true

# direction model
probability.direction-model.retrain-interval=6h
probability.direction-model.hold-hours=72
probability.direction-model.lookback-days=60
```

The flip's existing knobs (`probability.config-tag=v2-1to1-flip`, `probability.geometry.*`,
`probability.direction.invert=true`) stay in place and feed `FlipGenerator`, so the live experiment
is unchanged.

## Testing

Pure-logic, JVM-isolated, no user-visible state (consistent with the no-synthetic-data rule's
allowance for formula assertions):

- `DirectionModel` trains to separate a linearly-separable feature signal (mirrors the existing
  `LogisticWinModel` test).
- `FeatureDirectionGenerator` returns LONG when model > 0.5, SHORT when < 0.5, `empty()` when
  untrained.
- **`FlipGenerator` regression** — identical entry/stop/target/direction to the current inline
  logic for fixed inputs (proves the refactor did not move v2).
- Trainer label logic — hardcoded forward-candle fixtures: a path reaching +risk before −risk →
  label 1; stop-first on a straddle bar → 0.

## No-synthetic-data compliance

- Training consumes **real historical candles** and labels derived from **real forward price
  action** — no fabricated rows.
- Candidates are shadow-only, tagged, never surfaced as live trades.
- If the model cannot train, v3 candidates are **skipped** (honest absence), never faked.

## Risks

- **Look-ahead bias** — the main hazard. Features strictly from bars ≤ t, label strictly from bars
  > t. A shared forward-walk helper keeps trainer labels consistent with `ShadowOutcomeEvaluator`.
- **Disturbing v2** — the flip refactor is behavior-preserving; the regression test enforces it.
- **In-sample optimism** — the model trains on the same symbols it predicts; the 1-week
  out-of-sample shadow win rate is the verdict, not training accuracy (the same discipline that
  caught the flip's 77% → 50% regression).

## Verification

1. signal-service tests green (existing + new).
2. `cd services/signal-service && mvnd compile` → BUILD SUCCESS.
3. After deploy, within one scan both `v2-1to1-flip` and `v3-feature-dir` rows accrue as PENDING
   (`SELECT config_tag, count(*) FROM probability_candidates GROUP BY config_tag`).
4. Both calibration curves return: `…/calibration` (v2 default) and `…/calibration?tag=v3-feature-dir`.
5. Cross-service log audit: no new ERROR/WARN from the scan or trainer.

## Review cadence

Fold a `v3-feature-dir` line into the existing 6-hour loop report alongside v2. Decision point at
~1 week: does feature-derived direction hold a win rate meaningfully above 50% out-of-sample? If
yes, it becomes the candidate for a live EV gate (Phase 3); if it also regresses to ~50%, the raw
features carry no more directional edge than `overallScore`, and the next experiment changes what a
candidate *is* (real-detector-driven) rather than the direction source.
