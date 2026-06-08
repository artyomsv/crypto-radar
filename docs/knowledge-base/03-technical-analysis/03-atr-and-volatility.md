# ATR and Volatility

> Average True Range measures realized volatility in price units, not percent. ATR-relative thresholds are how you write a single rule that holds across BTC at $100k and a low-cap perp at $0.50 without rewriting constants.

## Definition

**True Range** is the per-bar volatility measure introduced by J. Welles Wilder in *New Concepts in Technical Trading Systems* (1978):

```
TR[i] = max(
  high[i] - low[i],
  abs(high[i] - close[i-1]),
  abs(low[i] - close[i-1])
)
```

The three components handle the three ways volatility can manifest: the bar's intraday range, a gap-up from prior close, and a gap-down from prior close. Crypto markets don't truly "gap" (24/7 trading), so the second and third components mostly matter in fast moves where the bar's open is far from prior close.

**Average True Range** is a Wilder-smoothed (α = 1/N) average of TR over N bars. We use N=14. Implemented in [`IndicatorCalculator.calculateATR`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) following the canonical recursive form. ([StockCharts — ATR](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/average-true-range-atr))

## Why ATR-relative thresholds beat absolute %

A 0.5% pierce of a swing low on BTC at $100,000 is a $500 wick. The same 0.5% on LINK at $15 is a $0.075 wick. Both are dollar amounts; neither tells you whether the move is meaningful relative to *that symbol's recent typical volatility*.

ATR normalizes by recent volatility. A "0.3 ATR" pierce means the wick poked through the level by 30% of the typical recent bar range — same scaled interpretation on any symbol. This is exactly the design rationale for `LiquiditySweepDetector.MIN_PIERCE_ATR_FRACTION = 0.3`: a sweep that doesn't pierce by at least a quarter-ATR is indistinguishable from ordinary intrabar jitter.

The same logic applies to **stop placement**. A stop at "0.5 ATR beyond the swept low" automatically widens when the symbol's volatility expands and tightens when it contracts. Compare this to a fixed-percentage stop, which puts you inside the noise band in high-vol regimes and outside the move's natural drift range in low-vol regimes.

## ATR-based exit systems

Two adjacent concepts:

- **Chandelier Exit.** Charles Le Beau, popularized in Alexander Elder's books. Sets a trailing stop at `highest_high(N) - K * ATR(N)` for a long position. Default K is typically 3. The stop ratchets *up* as new highs print but never down. ([StockCharts — Chandelier Exit](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-overlays/chandelier-exit))
- **Keltner Channels.** Chester Keltner, 1960; modernized by Linda Bradford Raschke to use ATR. Bands above/below an EMA at `EMA(N) ± K * ATR(N)`. Volatility-adapted channel that contracts and expands with realized vol. ([StockCharts — Keltner Channels](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-overlays/keltner-channels))

projectr-x's trail system is closer to Chandelier philosophy than Keltner — it ratchets a stop with the favorable excursion rather than computing bilateral channels around price.

## What we do today (in projectr-x)

ATR runs through three load-bearing places in the codebase:

1. **`LiquiditySweepDetector` filter and stop logic** ([file](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/LiquiditySweepDetector.java)):
   - `MIN_PIERCE_ATR_FRACTION = 0.3` — sweep pierce must clear 30% of one ATR to qualify.
   - `MIN_ATR_PCT = 0.003` — skip detection entirely when ATR/price < 0.3% (low-ATR regimes produce wicks indistinguishable from noise).
   - `STOP_BUFFER_ATR = 0.5` — stop placed 0.5 ATR beyond the trigger bar's low (for LONG) or high (for SHORT). Widened from 0.2 to 0.5 after outcome analysis: 23 of 53 stops at 0.2 ATR had MAE ≥ 1.5R, i.e. price pierced the stop substantially then reversed.

2. **`TrendContinuationDetector` stop sizing**: `STOP_ATR_MULTIPLE = 1.5`. Stop placed at `entry ± 1.5 * ATR(14)`. This combines with R-multiple math (`TARGET_R_MULTIPLE = 5.0`) so the structural target = max of the resistance level and the 5R extension.

3. **Trailing stop math in `OutcomeEvaluator` and `TrailCalculator`** (`shared-trade-core/`): the ladder activates at MFE ≥ 1R with a 0.5R offset, widens to 1.0R offset at MFE ≥ 2.5R. Risk units (R) are themselves ATR-scaled at the detector layer, so the trail effectively breathes with volatility.

4. **Stagnation exit in `OutcomeEvaluator`** (added in v4): closes a trade with `final_exit_reason='STAGNATION'` when age ≥ 45 min, MFE < 0.2%, MAE > -0.3%. The 0.2/0.3% thresholds are percent-of-price, *not* ATR-relative — a known limitation, since they're tight for BTC but loose for a low-vol altcoin. A future revision will scale these by ATR to match the rest of the stack.

## When ATR-based rules fail

- **ATR is backward-looking.** A 14-bar window means the indicator lags by ~7 bars on average. After a sudden volatility regime break (e.g., a CPI print, an exchange hack), ATR understates current vol for several bars. Stops sized to pre-event ATR can be inside the new normal range.
- **Vol clustering.** ATR averages TR linearly, so a single huge TR bar followed by quiet bars produces an ATR that overstates "typical" current volatility. Robust alternatives use median TR or trimmed mean, neither implemented here.
- **At funding-rate spikes.** The minute around 00/08/16 UTC on Bybit can produce a wick that contaminates the bar's TR even when "real" volatility is normal.
- **Symbol contamination.** Stale klines from a delisted symbol produce ATR values that look reasonable but are stale; the LS detector's `MIN_ATR_PCT` floor partly catches the case where ATR is anomalously small, but a frozen ATR at a once-normal level is undetected.

## Sources

1. Wilder, *New Concepts in Technical Trading Systems* (1978) — original publication introducing ATR. See `09-sources/01-books.md`.
2. [StockCharts ChartSchool — Average True Range](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/average-true-range-atr) — canonical reference for the formula and Wilder smoothing.
3. [StockCharts ChartSchool — Chandelier Exit](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-overlays/chandelier-exit) — operational guide to ATR-trailing stops.
4. [StockCharts ChartSchool — Keltner Channels](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-overlays/keltner-channels) — ATR-based channel construction, useful as a mental model for trail expansion.
5. Carver, *Systematic Trading* — explicitly ATR-relative sizing across asset classes; this rule comes straight from his framework. See `09-sources/01-books.md`.
6. Clenow, *Following the Trend* — uses 3-ATR trailing stops as the default exit. Our 0.5 ATR initial stop buffer (in the LS detector) plus the R-multiple trail produces a similar shape with different parameters. See `09-sources/01-books.md`.
