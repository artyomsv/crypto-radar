# Implemented Detectors — Code-Level Inventory

> Every threshold, every filter, every constant, with file:line references. When you change one of these numbers, update this doc in the same commit.

The signal-service has three signal-generation surfaces:

1. **Overview / dimension-scoring** — `SignalEngine.java` runs every ~5s, scores 6 dimensions per symbol, emits BUY/SELL/NEUTRAL.
2. **`LiquiditySweepDetector`** — fires on stop-hunt / pierce-and-reclaim patterns.
3. **`TrendContinuationDetector`** — fires on healthy pullbacks in established trends.

Every detector implements `TradeSetupDetector.detect(MarketContext) → Optional<TradeSetup>`. A TradeSetup carries entry/stop/target + `TrailConfig` (per-strategy trail parameters from `shared-trade-core`).

---

## 1. Dimension-Scoring (SignalEngine.java)

**File**: `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java`

### Overall score
```
overall_score = Σ(dimension_score × weight)   clamped to [-100, 100]
```
Weights from active `SignalConfig` (currently **v5**, see below). Hot-reloaded every 30s.

### Active v5 weights (2026-06-03)
| Dimension | Weight | Empirical W−L diff | Note |
|---|---|---|---|
| technical | 0.4375 | +15.7 | Strong discriminator |
| whale | 0.25 | +16.7 | Best single discriminator |
| derivatives | 0.1875 | +8.8 | Moderate |
| macro | 0.125 | +7.6 | Moderate |
| orderBook | 0 | 0.0 | Zeroed — alpha decays in seconds, useless at 1m |
| sentiment | 0 | −0.1 | Zeroed — pure noise in last 14d |

Pre-v5 weights distributed 0.1+0.1=0.2 to OB+Sentiment which empirically contributed nothing. v5 redistributed those proportionally.

### Alignment computation
Lines 516–547 of `SignalEngine.java`. Not Pearson correlation — it's a weighted directional-agreement metric:

1. For each dimension, contribute `(|score|/100) × weight` if direction matches `sign(overall_score)`, else multiply by `contradictionPenaltyMultiplier`.
2. `raw = (weightedStrength / totalWeight) × 100`
3. Apply `twoContradictionPenalty` if ≥2 dims oppose, else `oneContradictionPenalty` if 1.
4. `alignment = clamp(raw × outputScale, [minOutput, maxOutput])`

All thresholds in `config.alignment()` — hot-reloadable.

### Label thresholds (`determineSignalLabel`)
Regime-aware. Current v5 / CHOP defaults:

| Label | Score | Alignment |
|---|---|---|
| STRONG_BUY | ≥ 55 | ≥ 70 |
| BUY | ≥ 25 | ≥ 55 |
| NEUTRAL | otherwise | |
| SELL | ≤ −15 | ≥ 55 |
| STRONG_SELL | ≤ −40 | ≥ 70 |

**BULL regime override**: SELL bar reverts to symmetric (`strongSell≤−55, sell≤−30`) — counter-trend needs stronger evidence.
**BEAR regime override**: BUY tightened (`strongBuy≥70, buy≥40`) — don't catch falling knives.

### Trade level placement (`populateTradeLevels`)
- **Risk** = `max(atr14 × atrStopMultiple, support_distance × supportStopAtrBuffer, entry × MIN_RISK_PCT)` where MIN_RISK_PCT = 0.015 (1.5%).
- **Target** = `max(entry + risk × MIN_RR, structural_resistance)` where MIN_RR = 2.0.
- Stops never inside MIN_RISK_PCT band; targets never closer than 2:1 RR.

---

## 2. LiquiditySweepDetector

**File**: `services/signal-service/src/main/java/com/cryptoradar/signal/detector/LiquiditySweepDetector.java`

Detects pierce-and-reclaim reversals at structural swing levels — the geometry from the ICT/SMC literature, with rigorous filtering. See `02-strategies/03-liquidity-sweep-and-reversal.md` for theory.

### Constants (file:line refs)

| Constant | Value | Purpose | Line |
|---|---|---|---|
| `MIN_BARS_REQUIRED` | 8 | Skip cold-start | 37 |
| `MIN_PIERCE_ATR_FRACTION` | 0.3 | Pierce must be ≥0.3 × ATR | 46 |
| `MIN_WICK_BODY_RATIO` | 0.5 | Wick must be ≥0.5 × body | 49 |
| `MIN_RECLAIM_BODY_RATIO` | 0.3 | Close reclaims ≥30% of bar | 56 |
| `MIN_ATR_PCT` | 0.003 | Skip if ATR < 0.3% of price | 64 |
| `MAX_DRIFT_PCT` | 0.5 | Entry within 0.5% of trigger | 72 |
| `DIM_DERIVATIVES_TOLERANCE` | 5.0 | Deriv can't oppose by >5 | 80 |
| `STOP_BUFFER_ATR` | 0.5 | Stop = swing ± 0.5 × ATR | 87 |
| `TARGET_R_MULTIPLE` | 5.0 | Target floor at 5R | 89 |
| `STRONG_SIGNAL_ALIGNMENT` | 70 | Required for STRONG label | 90 |
| `LS_MIN_RISK_PCT` | 0.015 | LS-specific risk floor | 103 |
| `MIN_VOLUME_RATIO` | 1.3 | Vol ≥1.3× avg(3 prior bars) | 115 |

### TrailConfig
Uses `TrailConfig.DEFAULT` from shared-trade-core: `(1.0, 0.5, 0.5, 2.5, 1.0)` — 0.5R offset, with second rung at 2.5R MFE widening to 1.0R.

### Empirical performance (14d)
| Cell | n | total R |
|---|---|---|
| TRX SHORT LS | 3 | +3.20 |
| XLM LONG LS | 2 | +2.87 |
| BTC SHORT LS | 3 | +2.80 |
| 9 other cells | small n | mixed |

Lower frequency than TC but higher per-trade R. Strategy is selective by design.

---

## 3. TrendContinuationDetector

**File**: `services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java`

Fires on healthy pullbacks in established HTF trends. See `02-strategies/01-trend-following.md` for theory.

### Direction
- LONG: `sma50 > sma200 AND price > sma50`
- SHORT: `sma50 < sma200 AND price < sma50`

### Filters

| Constant | Value | Purpose |
|---|---|---|
| `MIN_PULLBACK_PCT` | 0.3% | Too shallow = not a real pullback |
| `MAX_PULLBACK_PCT` | 2.0% | Too deep = trend break |
| `IDEAL_PULLBACK_MIN/MAX` | 0.5–1.5% | +5 alignment bonus when inside |
| `RSI_MIN` / `RSI_MAX` | 35–65 | Avoid overbought tops & panic bottoms |
| `DIM_TECHNICAL_MIN` | 20 | Tech score must support direction |
| `DIM_DERIVATIVES_TOLERANCE` | 15 | Deriv can't oppose by >15 |
| `DIM_WHALE_TOLERANCE` | 20 | Whale can't oppose by >20 |
| `STOP_ATR_MULTIPLE` | 1.5 | Risk = 1.5 × ATR14 |
| `TARGET_R_MULTIPLE` | 5.0 | Target floor at 5R |
| `STRONG_SIGNAL_ALIGNMENT` | 65 | STRONG threshold |

### TrailConfig (changed 2026-06-03)
```java
TC_TRAIL = new TrailConfig(1.0, 0.5, 0.75, 2.5, 1.0);
```
Wider initial offset (0.75R vs DEFAULT 0.5R) for trend-continuation specifically. Right-tail-preserving change motivated by:
- 124 trail wins averaged +0.88R in 14d
- 8 target hits averaged 11.79% MFE — meaningful right tail to preserve
- 0.5R offset risked clipping the right tail

### Empirical performance (14d)
Workhorse strategy — most of the engine's R-volume.

| Cell | n | total R |
|---|---|---|
| BCH SHORT TC | 16 | +12.76 |
| LTC SHORT TC | 22 | +7.11 |
| XLM LONG TC | 11 | +7.04 |
| DOGE SHORT TC | 21 | +5.27 |
| BTC SHORT TC | 23 | **−5.09** (9 stagnations, fixed by ATR rule) |
| TRX LONG TC | 17 | **−3.59** (11 stagnations, fixed by ATR rule) |

---

## OutcomeEvaluator (the feedback loop)

**File**: `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java`

Runs every 60s with 30s startup delay. Walks 1m candles forward through every PENDING outcome.

### Per-bar processing order
1. `updateExcursions` — MFE/MAE tracking
2. `updateTrailingStop` — ratchet trail if MFE crosses next rung
3. `detectHit` — bar's high/low vs current effective stop, current target
4. If neither hit and bar is last bar: `stagnationExitIfEligible`
5. If no hit and outcome aged ≥7d: EXPIRED

### Trail math (lines 198–240)
```
mfe_r = max_favorable_pct / risk_pct
rung = floor((mfe_r - activation_r) / step_r)
stop_r = activation_r + rung × step_r - offset_r
stop$ = entry + stop_r × risk    (LONG; subtract for SHORT)
```
Monotonic — never loosens. Per-row config from `outcome.trailActivationR/StepR/OffsetR`. Second rung at `widerOffsetActivationR=2.5R → widerOffsetR=1.0R` is a global engine knob from `SignalConfig.trail()`.

### Stagnation rule (ATR-scaled, 2026-06-03)
Fires when ALL true:
- Age ≥ `stagnationMinAgeMinutes` (45 default)
- `MFE < 0.25 × ATR(45 1m bars, % of entry)` — ATR-scaled (or 0.2% absolute fallback if ATR uncomputable)
- `MAE > −0.4 × ATR(...)`  — ATR-scaled (or −0.3% absolute fallback)

Falls back to absolute thresholds when there are <46 bars or entry price is zero. See `04-quant-methods/03-triple-barrier-labeling.md` for theory.

### Target-first-when-trail-active rule (lines 151–161)
If both `targetHit` and `stopHit` occur in the same bar:
- Trail not active → STOP wins (pessimistic, no prior MFE evidence)
- Trail active → TARGET wins (optimistic — prior MFE excursion already proved range)

Justified because the trail level only ratchets via a *prior* MFE excursion — by the time both fill conditions are met in one bar, the upper level was clearly within range earlier.

### Fee normalization
```
feesInR = (feesBps / 10000) / risk_pct
```
A 10bps round-trip on 1% risk eats 0.1R out of every closed trade. Stored per-row in `signal_outcomes.fees_bps_round_trip` (default 10).

---

## Execution-side gates (trade-execution-service)

Before any signal reaches Bybit, it passes through 5 gates. All log to `execution_events` after 2026-06-03 (`SIGNAL_BLOCKED_*` event types, coalesced to 1/symbol/gate/60s).

### Gate 1 — Alignment floor
`SignalSubscriber.isBelowAlignmentFloor` — overview signals only (detector alerts bypass).
- Default: `executionSettings.alignmentFloor = 70`
- **Empirically should be 55** based on 14d data — productive bucket is 50–70

### Gate 2 — Symbol performance
`SymbolPerformanceGate.isSuppressed` — currently blocks BTC SHORT TC, ZEC SHORT TC, TRX LONG TC.
- Default: lookback=10 closed signals, threshold=−3R, cache=30s TTL
- Reads `signal_outcomes` directly via native SQL (shared DB).

### Gate 3 — Detector confluence
`DetectorConfluenceCheck.requiresConfluence` — trend-continuation requires an open `dimension-scoring` outcome in the same symbol+direction within 15min.
- Currently the BIGGEST blocker per CHOP-regime telemetry

### Gate 4 — Daily PnL halt
`DailyPnlCalculator.todayPnlPercent` — halts when realized PnL since UTC midnight crosses `max_daily_loss_percent` (10% default for account 297).
- **Never triggered in 14d**. Audited 2026-06-03.

### Gate 5 — Dedup
Symbol × direction × strategy must not already have an open trade. 7 hits in 14d.

---

## Known design constraints (not bugs)

These were briefly tracked as tech debt; they are deliberate trade-offs in the current evaluator. Listed so future work can decide whether to lift them.

### 25-hour gap-recovery window
`OutcomeEvaluator` fetches the last `CANDLE_FETCH_LIMIT = 1500` 1m candles per run (~25 hours). If signal-service is down longer than that, stop/target hits inside the unrecovered window are missed — the row stays PENDING until `MAX_HOLD = 7 days` and is then marked `EXPIRED` at the current price. **Impact**: hit rate is understated after a long outage; absolute R is understated. **Mitigation**: keep uptime high; if a real outage happens, manually re-evaluate the affected rows by widening `CANDLE_FETCH_LIMIT` temporarily. Genuine lift would require a time-range fetch (`from=lastEvaluatedAt`) on the market-data API.

### Null AI analysis on outcome creation
Gemini analysis is fired on the same cycle as the signal transition but completes asynchronously. The outcome row is persisted *before* the AI rationale returns, so `ai_analysis` is often NULL at insert. The tracker dedups on subsequent cycles, so the rationale never backfills. **Impact**: cosmetic — the AI rationale is a rationale view, not a metric input. **Lift**: would require a small `updateAiAnalysis(signalId, text)` repository method called from a delayed scheduler.

### Slippage not modeled
`realized_r_multiple` is net of round-trip fees (`feesInRUnits`) but assumes perfect fills at exactly `stop_price` / `target_price`. Real fills slip by 0.5–3 bps on average, more during fast markets. **Impact**: absolute R is overstated by ~5–10% vs live. **Lift**: a `scheduler.outcome.slippage-bps` config knob would shift fills inward by that amount before P&L math.

### Same-bar stop+target with trail INACTIVE → STOP wins (pessimistic)
Resolved by the v5 target-first-when-trail-active rule. The bias remains for trail-inactive trades, but the trail-inactive path is shorter-lived now that activation is 1R.

## Cross-references

- `02-strategies/01-trend-following.md` — theory behind TC detector
- `02-strategies/03-liquidity-sweep-and-reversal.md` — theory behind LS detector
- `05-risk-and-execution/03-trailing-stops.md` — trail math and history of our v5 fix
- `04-quant-methods/03-triple-barrier-labeling.md` — theory behind OutcomeEvaluator
- `04-empirical-findings.md` — what these detectors actually did in last 14d
- `03-roadmap-ideas.md` — what's worth adding next
