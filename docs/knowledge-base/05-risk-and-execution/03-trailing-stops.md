# Trailing Stops

> Stops that move with the trade's high-water mark. Done right, they preserve the right tail that drives long-run expectancy. Done wrong, they cut runners early and you're trading a worse strategy than no trail at all.

## Definition

A **trailing stop** is a protective stop whose price is updated as the trade moves favorably, never adversely. The simplest version: "stop = entry, ratchet up by X every time price prints a new high." A trade exits when the price retraces to the current stop level.

Three parameters define a trailing stop:

1. **Activation threshold.** The favorable excursion required before the trail engages at all. Until activation, the initial stop holds.
2. **Step.** The increment at which the trail ratchets — usually expressed in price multiples (ATR) or in R-multiples of the initial risk.
3. **Offset.** The distance the trail keeps behind the current peak. Smaller offset → tighter trail → exits sooner, gives back less, but cuts winners more often.

The literature splits trailing-stop designs into two families:

- **Volatility-calibrated trails.** The offset is set in units of recent realized volatility — typically `N × ATR`. Andreas Clenow's *Following the Trend* (2013) is the canonical reference, advocating a `3 × ATR` trail on weekly trends. This adapts to regime: a calm tape gets a tight trail, a wild tape gets a wide one.
- **Fixed-fraction-of-R trails.** The offset is set as a fraction of the initial risk `R`. Van Tharp's framework treats every variable in R-units, including stops.

Both families work in their natural horizon. ATR trails work well on multi-day-to-week horizons where ATR is a stable estimate. R-fraction trails work well on hours-to-days horizons where R is well-defined at entry and ATR drift is significant compared to R.

A more advanced design is the **multi-rung trail**: different offsets activate at different MFE levels. Tight offset early to bank some win-rate-by-construction; widen the offset on the right tail to let runners run. This is the design we ship.

## When it works

- **Trending markets.** Trails are designed for trends. In a clean directional move, the trail ratchets and the trade closes at trail-out near the peak, not at target. (Empirically: 2.9% of v4 trades hit TARGET; most positive R comes from TRAIL exits.)
- **Long right-tail distributions.** Crypto's daily-return distribution has skew and kurtosis that produce occasional 5–10R moves. A trail's job is to preserve those without giving them back.
- **You want to compound expectancy, not maximize win rate.** Trails reduce win-rate (some trades stop out on noise after a small MFE) and increase the right tail (some trades capture more than the original `+2R` target). Net: positive on expectancy, negative on win-rate.

## When it fails

- **Tight offset on a noisy chop.** A 0.5R offset in a tape that whipsaws regularly will trail-out at small wins repeatedly while never catching the bigger trend. ATR-relative offsets fix this; fixed-fraction offsets don't.
- **Trail activates too early.** Activation at +0.3R on a noisy tape means most trades activate and then stop out on the first reversal, before the move has any real direction. Our v4 default activates at +1R for this reason.
- **No second rung.** A single-offset trail (e.g. fixed 0.5R) cuts runners. A trade at MFE = +4R with a 0.5R offset trails to +3.5R, but the trade might really have been +8R if you'd given it more room. The second rung (widen the offset above some MFE threshold) is the fix.
- **Trail logic silently broken.** The single biggest production bug we've shipped: pre-v5, `MarketDataClient.getLastPrice` parsed the wrong response shape and returned null for every lookup. `TrailMirror.processTrade` early-returned on every tick. Zero of 35 closed trades had `trail_triggered_at` set. The trail system was inert for an entire deployment cycle. Lesson: trail telemetry (count of `trail_triggered_at IS NOT NULL` per day) is a load-bearing health metric, not a nice-to-have.

## What we do today (in projectr-x)

The trail system has two implementations: a "tracking" trail in `signal-service` (computes what the trail *would* do on `signal_outcomes` rows) and a "mirror" trail in `trade-execution-service` (actually moves the Bybit stop on live positions). Both share the math in `shared-trade-core/TrailCalculator.java` so they cannot drift.

**Default (`TrailConfig.DEFAULT`):**

| Parameter | Value | Meaning |
|---|---|---|
| `activationR` | 1.0 | Trail engages once MFE ≥ 1R |
| `stepR` | 0.5 | Trail advances in 0.5R increments |
| `offsetR` | 0.5 | Initial offset: trail keeps 0.5R behind the peak |
| `widerOffsetActivationR` | 2.5 | Second rung: once MFE ≥ 2.5R… |
| `widerOffsetR` | 1.0 | …offset widens to 1.0R, giving runners more room |

**Trend-continuation override:** the `TrendContinuationDetector` constructs its own `TrailConfig` with `offsetR = 0.75` (a recent calibration tightening). The reasoning: TC entries are slower trends that don't need to give back 1R once they're well-into the move; the 1.0R wider-offset is too generous for the strategy's actual MFE distribution.

**Order of operations in `OutcomeEvaluator.processBar`:**

1. Check if the bar's low crossed the dynamic stop → exit at dynamic stop (TRAIL).
2. Check if the bar's high crossed the target → exit at target (TARGET).
3. If both — target-first when trail was active, because the trail was prior-bar-ratcheted and would have triggered before the target.
4. Update MFE/MAE if not exiting.
5. Ratchet trail if MFE has crossed a new step.

**Mirror layer:** `trade-execution-service`'s `TrailMirror` queries `signal_outcomes` for the current `dynamic_stop_price` and pushes it to Bybit via `BybitV5RestClient.setTradingStop` whenever it changes. The mirror is now active (v5 fix) — pre-v5 it was silently inert.

**Stagnation companion:** `OutcomeEvaluator` has a stagnation exit that closes a trade with `final_exit_reason = STAGNATION` if MFE < 0.25 × ATR(14) and MAE > −0.4 × ATR(14) over 45 consecutive 1m bars. This is not strictly a trail — it's the **time barrier** of the triple-barrier framework (`04-quant-methods/03-triple-barrier-labeling.md`).

Code references:
- `shared-trade-core/src/main/java/com/cryptoradar/core/TrailCalculator.java` — single source of trail math, used by both services.
- `shared-trade-core/src/main/java/com/cryptoradar/core/TrailConfig.java` — `DEFAULT` config above; new `widerOffsetActivationR` / `widerOffsetR` fields for the v4 second rung.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java` — applies the trail on the tracking side.
- `services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java` — overrides the trail with `offsetR = 0.75`.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/TrailMirror.java` — mirrors the trail to Bybit. Re-activated in v5 after the price-parse fix in `MarketDataClient`.

## Implementation sketch (calibrations under consideration)

- **ATR-relative offsets.** Currently the offset is in R-multiples, which compounds with the ATR-scaled initial-stop placement to be implicitly ATR-relative. A more explicit design would set `offsetR = max(0.5R, 0.3 × ATR / risk_per_unit)` so the trail stays out of noise even when the initial R is tight relative to recent ATR. Effort: ~half a day in `TrailCalculator`; the harder part is validating empirically.
- **Three-rung ladder.** Activate at +1R / 0.5R offset; widen at +2.5R / 1.0R; widen further at +5R / 1.5R. Worth doing only once we have ≥ 30 trades hitting MFE > 5R; current count is ~3.
- **Asymmetric trail per direction.** SHORTs in crypto have different volatility-of-MFE than LONGs (negative skew is real, positive skew is less so). Per-direction trail configs are a 2-day change but require ≥ 50 SHORT closed outcomes to calibrate — we don't have that yet.

## Sources

1. [Clenow, A. F. *Following the Trend: Diversified Managed Futures Trading* (Wiley, 2013)](https://www.wiley.com/en-us/Following+the+Trend%3A+Diversified+Managed+Futures+Trading-p-9781118410851) — the 3-ATR trail benchmark. Also the canonical book on long-horizon trend-following with explicit trail math.
2. [Tharp, V. K. *Trade Your Way to Financial Freedom*, 2nd ed. (McGraw-Hill, 2006)](https://www.amazon.com/Trade-Your-Way-Financial-Freedom/dp/007147871X) — the R-multiple framing of stops, which underlies our `TrailConfig` design.
3. [Faith, C. *Way of the Turtle* (McGraw-Hill, 2007)](https://www.amazon.com/Way-Turtle-Strategies-Legendary-Traders/dp/0071486646) — Dennis & Eckhardt's original Turtle system rules, including ATR-based stop placement.
4. [Incredible Charts, "ATR Trailing Stops"](https://www.incrediblecharts.com/indicators/atr_average_true_range_trailing_stops.php) — clean reference for the ATR-trail formula and the Wilder smoothing convention.
5. [Pruitt, G. "Free Trend Following System with Indicator Tracker"](https://georgepruitt.com/free-trend-following-system-with-indicator-tracker/) — practitioner write-up of Clenow's 50-day Donchian + 3-ATR trail with QuantConnect-style code.
6. [FXOpen, "Turtle Trading: System, Rules, and Strategy"](https://fxopen.com/blog/en/turtle-trading-system-rules-and-strategy/) — modern restatement of the Turtle rules with ATR sizing details.
