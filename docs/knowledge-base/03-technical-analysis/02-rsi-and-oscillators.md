# RSI and Oscillators

> Momentum oscillators measure the *velocity* of price change rather than its level. RSI is the workhorse; the textbook 30-70 levels assume mean-reversion, but trend-continuation entries do better with a narrower 35-65 "no-panic" band.

## Definition

An oscillator is a bounded function of price that maps recent price action to a range like [0, 100]. The bounded range gives "overbought" and "oversold" reference levels that the unbounded price chart doesn't have.

- **RSI (Relative Strength Index).** Wilder, 1978. Ratio of average gains to average losses over a window, smoothed via Wilder's recursive method. Implemented in [`IndicatorCalculator.calculateRSI`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java).
- **Stochastic.** Lane, 1950s. Position of current close in the recent high-low range, then optionally smoothed. Two-line variant: %K (raw) and %D (3-period SMA of %K). Not implemented in our stack.
- **MACD (Moving Average Convergence/Divergence).** Difference of two EMAs (12 and 26), with a 9-period signal line and histogram. Implemented in [`IndicatorCalculator.calculateMACD`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java).
- **CCI, Williams %R, ROC** — variations on the same theme. Not implemented.

## RSI math, briefly

For period N (we use N=14):

```
change[i] = close[i] - close[i-1]
gain[i]   = max(change[i], 0)
loss[i]   = max(-change[i], 0)
avgGain[N] = sum(gain[1..N]) / N        # seed: SMA
avgLoss[N] = sum(loss[1..N]) / N
avgGain[i] = (avgGain[i-1] * (N-1) + gain[i]) / N   # Wilder smoothing
avgLoss[i] = (avgLoss[i-1] * (N-1) + loss[i]) / N
RS  = avgGain / avgLoss
RSI = 100 - 100 / (1 + RS)
```

Wilder's smoothing is mathematically equivalent to an EMA with α = 1/N, **not** α = 2/(N+1). Almost every chart platform implements this correctly. The line in [`IndicatorCalculator.calculateRSI`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) follows the canonical recursive form.

## Why 35-65 instead of textbook 30-70

Wilder's original publication marks 70 as overbought and 30 as oversold — the **mean-reversion** reading: prices above 70 are "too high" and should snap back, below 30 are "too low" and should rebound. ([StockCharts ChartSchool — RSI](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/relative-strength-index-rsi); [Wikipedia — Relative Strength Index](https://en.wikipedia.org/wiki/Relative_strength_index))

The mean-reversion reading fails reliably in trending regimes. In a strong uptrend, RSI can sit between 60 and 80 for **weeks**, ticking up to 80+ on every advance and rarely cooling below 50. Mechanically betting the snapback at 70 is a hit-by-trends strategy. ChartSchool calls this "the most expensive mistake" of RSI usage.

The fix used by trend-following practitioners is to **flip the interpretation**: in an established uptrend, the goal isn't to fade RSI extremes — it's to *enter* on RSI pullbacks that stay within the trend's normal band. Empirically that band, on 4h crypto data, is roughly **40-70 for uptrends and 30-60 for downtrends**. A narrower 35-65 symmetric window:

- Avoids the worst overbought-panic-bottoming false signals (skips RSI < 35 = capitulation tape, skips RSI > 65 = blow-off top).
- Stays in the meat of trend-continuation entries (pullbacks that reset momentum without breaking the trend).
- Is symmetric, simplifying the logic for both LONG and SHORT.

This is the rationale baked into `TrendContinuationDetector` constants `RSI_MIN = 35.0` and `RSI_MAX = 65.0`. The detector also requires SMA50/SMA200 trend agreement — the RSI filter assumes a trending regime, so it must be paired with a trend gate.

## MACD — the lower-precision cousin

MACD is a slower, less-precise momentum indicator than RSI for entry timing, but better for **divergence detection**. When price makes a new high but the MACD histogram peaks lower, the trend's momentum is fading. We compute MACD via [`IndicatorCalculator.calculateMACD`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) (12/26/9 default) but do not currently use it as a gate in either detector. It contributes to the `Technical` dimension score in `SignalEngine`.

Stochastic is not in the stack; it adds little incremental signal beyond RSI for our use case.

## When RSI works

- **Trend-continuation entries.** Pullback to SMA20 in an established trend with RSI cooling into the 35-65 band = textbook setup. Used by `TrendContinuationDetector`.
- **Bullish/bearish divergence at multi-day swing points.** Price extending to a new low but RSI making a *higher* low is a classic reversal precursor. Not currently coded but visible in our raw-data dumps.

## When RSI fails

- **In strong trends.** RSI can pin > 70 or < 30 for many bars. Counter-trend entries here produce avalanching losses.
- **In low-volatility chop.** RSI hovers around 50 with no useful signal. The `MarketRegimeService` CHOP classification correlates with this.
- **On stale or delisted symbols.** When kline data freezes (XMRUSDT pre-removal), RSI calculations return stale values that look real. The detector's `Inputs.from` null-check catches missing values; it does not catch *stale* values.
- **At very short windows.** RSI(7) on 1m candles is jitter, not signal. We always use RSI(14) on 4h or higher.

## What we do today (in projectr-x)

- [`IndicatorCalculator.calculateRSI`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) — Wilder-smoothed RSI(14) on 4h closes, computed by `AnalyticsService`.
- [`TrendContinuationDetector`](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java) — `RSI_MIN = 35.0`, `RSI_MAX = 65.0` as a hard filter alongside the SMA50/SMA200 trend gate. The 0.3-2.0% pullback to SMA20 plus this RSI band defines the entry window.
- `MACD` — computed but consumed only by the `Technical` dimension score, not as a detector gate.

## Sources

1. Wilder, *New Concepts in Technical Trading Systems* (1978) — the original RSI publication. See `09-sources/01-books.md`.
2. [StockCharts ChartSchool — Relative Strength Index (RSI)](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/relative-strength-index-rsi) — canonical online reference; covers Wilder's smoothing and the trend-vs-reversal interpretation differences.
3. [Wikipedia — Relative Strength Index](https://en.wikipedia.org/wiki/Relative_strength_index) — clean math, alternative period configurations.
4. [Fidelity — RSI Learning Center](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/RSI) — discusses the wider 65/35 vs 70/30 thresholds and when each makes sense.
5. [TradingView — RSI documentation](https://www.tradingview.com/support/solutions/43000502338-relative-strength-index-rsi/) — the platform-standard implementation; useful sanity check when validating our numbers.
