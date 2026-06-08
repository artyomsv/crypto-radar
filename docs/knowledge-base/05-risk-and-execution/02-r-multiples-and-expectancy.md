# R-Multiples and Expectancy

> Van Tharp's framework: every trade is measured in multiples of its initial risk. Win-rate is a vanity metric; expectancy (avg R) is what compounds.

## Definition

Van K. Tharp's *Trade Your Way to Financial Freedom* (1998, 2nd ed 2006) introduced the **R-multiple** as the unit for evaluating a trade. The setup:

- At entry, define **initial risk `R`** = the dollar (or %) distance from entry to initial stop. `R` is fixed at the moment of trade entry. Once the trade is open, `R` does not change even if you move the stop.
- The **outcome in R-multiples** is the realized PnL divided by `R`. A trade that makes 2× the initial risk is `+2R`; one that loses exactly the initial stop is `−1R`; a partial-stop is `−0.4R` and so on.

The R-multiple has three properties that make it the right unit for system evaluation:

1. **Comparable across symbols and regimes.** A 1.5% move in BTC and a 6% move in SOL with appropriately-scaled stops are both `+2R`. Aggregating raw PnL conflates volatility with skill; aggregating R-multiples does not.
2. **Decouples direction call from sizing decision.** R-multiples measure the *quality of the signal*, independent of how big you bet. You can analyze the signal layer (does my detector pick winners?) separately from the sizing layer (how big should I bet on each winner?). They are different problems with different evidence requirements.
3. **Defines expectancy unambiguously.** Tharp's **expectancy** = `E[R] = (win_rate × avg_win_R) − (loss_rate × avg_loss_R)`. This is the average R-multiple per trade. *This* is what compounds capital — not win rate, not avg-win-size, not risk-reward-ratio. Expectancy.

The critical re-framing Tharp performs: **win rate alone tells you almost nothing.** A 70% win rate strategy that wins `+0.5R` per winner and loses `−1.5R` per loser has expectancy `0.7·0.5 − 0.3·1.5 = −0.1R` — *negative*. A 35% win rate strategy that wins `+3R` per winner and loses `−1R` per loser has expectancy `0.35·3 − 0.65·1 = +0.40R` — positive and material. The second strategy is profitable; the first is not. They have nothing to do with each other except for sharing the same vocabulary.

Most retail trading literature reverses this — preferring "high-probability setups" (high win rate) over "high-expectancy setups." Tharp's framework is the antidote.

## When it works

- **You have a defined `R` at entry.** Any system with stops does. R-multiples are well-defined for trend-following, mean-reversion, breakout, scalping — anything except pure HFT.
- **You're comparing systems or detectors.** A strategy's expectancy and standard-deviation-of-R are stable across symbols and time in a way raw PnL is not.
- **You're computing Kelly sizing.** R-multiples and Kelly are made for each other: Kelly's `μ/σ²` is exactly `E[R] / Var(R)` when bets are R-scaled.

## When it fails

- **Stops are not real.** If your "stop" is a mental price you don't honor, `R` is fiction. Honest R-multiples require honest stops.
- **R is reset mid-trade.** If you tighten the stop, that's a different (smaller) R. The R-multiple framework demands the *initial* R as the unit, with subsequent risk management captured as `realized_pnl / initial_R`, not as a redefined R.
- **Fees not included.** A `+1R` trade that pays 0.11% Bybit round-trip on a 1.5% risk distance has actually netted `+1R − 0.073R = +0.927R`. The system that ignores fees inflates expectancy by ~7–15% depending on R-distance and venue. Our `OutcomeEvaluator.feesInRUnits` computes this explicitly.
- **Asymmetric trail behavior.** A trail-out at `+0.5R` after MFE was `+2.0R` is `+0.5R`, not `+2R`. The R-multiple captures realized, not maximum favorable. This is correct but counterintuitive to traders who think they "should have gotten" the 2R.

## What we do today (in projectr-x)

R-multiples are the **primary** trade evaluation unit across the entire stack. The schema, the engine, and the metrics layer are all R-native.

**Database:** `signal_outcomes.realized_r_multiple` (numeric, net of fees) is the canonical outcome column. Computed by `OutcomeEvaluator` as:

```
realized_r = (closed_price − entry_price) / risk_per_unit × direction_sign − feesInRUnits
```

with `feesInRUnits` = `fees_bps_round_trip / 10000 / (risk_per_unit / entry_price)` (default 10 bps round-trip, ~0.073R on a 1.5% risk distance).

**Engine constants:**
- `MIN_RR = 2.0` — minimum reward-to-risk ratio. Every signal aims for at least `+2R` if it hits target.
- `MIN_RISK_PCT = 1.5%` — minimum risk distance, widened from 0.5% in v4 specifically to cut Bybit's 0.11% round-trip fee drag down to a non-dominant fraction of R.

**Empirical state (14-day v4 slice):**
- 272 closed signals
- Total: **+32R**
- Per-signal expectancy: **+0.118R**
- TARGET hit rate: **2.9%** (i.e. only 8 trades of 272 actually reached the original `+2R` target)
- TRAIL is doing the work — the bulk of positive R comes from trail-side exits, not target hits

This is a textbook Tharp-style positive-expectancy system: **low win rate by classical definition, high average win, dominated by the right tail.** The `+0.118R/signal` expectancy at ~20 signals/day across 13 symbols extrapolates (with caveats — see `06-deflated-sharpe.md`) to roughly `0.118 × 20 × 252 ≈ 595R/year` on the signal-layer expectancy alone, before any sizing decisions. At 1% risk per trade, that would be ~595% gross annual return. Obviously that number is too good to be true — it's the deflation that has to come from validation, not the engine being miscalibrated.

The key takeaway: **the engine is profitable because of its right tail, not its hit rate**. Any change that improves win rate at the cost of trimming the right tail (e.g. taking quick profits at +0.5R) destroys the entire system. The trail mechanics described in `03-trailing-stops.md` exist precisely to preserve that right tail.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java` — the R-multiple computation, including fee netting.
- `shared-trade-core/src/main/java/com/cryptoradar/core/RUnitMath.java` — pure R-multiple math (entry, stop, target, percent-of-R-from-price). Unit-tested.
- `db/init/signal-init.sql` — `signal_outcomes.realized_r_multiple`, `realized_pnl_pct`, `fees_bps_round_trip`.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/PerformanceMetricsService.java` — aggregates R-multiples into `PerformanceReport.{totalR, avgR, winRate, byExitReason, byAlignmentBucket}`.

## Implementation sketch (extensions)

Two refinements worth doing once sample size permits:

1. **R-bucket distribution charts** in the frontend. Histogram of realized R-multiples per strategy. Shape of the right tail is the system's edge; visualizing it makes regressions obvious.
2. **Per-cell expectancy with bootstrap CIs.** Each symbol × strategy × regime cell gets its own `E[R]` and a 95% CI from bootstrap resampling. Cells with CIs that straddle zero are not edges, regardless of point estimate.

Effort: ~2 days each.

## Sources

1. [Tharp, V. K. *Trade Your Way to Financial Freedom*, 2nd ed. (McGraw-Hill, 2006)](https://www.amazon.com/Trade-Your-Way-Financial-Freedom/dp/007147871X) — the canonical source for R-multiples, expectancy, and the 17-step trading model that places sizing above prediction.
2. [Van Tharp Institute, *Trade Your Way to Financial Freedom*](https://vantharpinstitute.com/product/trade-your-way-to-financial-freedom/) — publisher's page with summary of the framework.
3. [TraderLion, "R and R-Multiples"](https://traderlion.com/risk-management/r-and-r-multiples/) — clean practitioner introduction with worked examples.
4. [Trademetria, "What Are R-Multiples? The Key Metric Every Trader Should Know"](https://trademetria.com/blog/what-are-r-multiples-the-key-metric-every-trader-should-know/) — short, useful introduction with the standard formulas.
5. [EBC Financial Group, "Trade Your Way to Financial Freedom: Van Tharp's Guide"](https://www.ebc.com/forex/trade-your-way-to-financial-freedom) — summary of the position-sizing-over-prediction thesis.
6. [AbleWayTech, "Van Tharp Expectancy in Trading & Position Sizing"](https://www.ablewaytech.com/articles/van-tharp-trade-your-way-to-financial-freedom-expectancy-in-trading-amp-position-sizing) — worked-example article on the expectancy formula.
