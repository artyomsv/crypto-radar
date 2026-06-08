# Moving Averages

> Smoothed price series. Trade off lag for noise reduction. The choice of SMA vs EMA vs HMA matters less than whether the underlying horizon (50d, 200d, etc.) is the right one for the strategy.

## Definition

A moving average reduces high-frequency noise in a price series by computing a rolling aggregate over a fixed window. The major variants differ in how they weight observations within the window:

- **SMA (Simple Moving Average).** Equal weight to every value in the window. `sum(closes[i-N..i]) / N`. Maximum lag — the SMA does not respond to a new closing print until the entire window has rolled. Implemented in [`IndicatorCalculator.calculateSMA`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java).
- **EMA (Exponential Moving Average).** Recent observations weighted exponentially heavier than old ones. `EMA[i] = (price[i] - EMA[i-1]) * α + EMA[i-1]` with α = 2 / (N+1). Lower lag than SMA at the same N. Implemented in [`IndicatorCalculator.calculateEMA`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java).
- **WMA (Weighted Moving Average).** Linearly decreasing weights (most recent = N, oldest = 1). Between SMA and EMA in responsiveness. Not implemented in our stack.
- **HMA (Hull Moving Average).** A double-WMA construction designed to reduce lag almost to zero while keeping smoothness. `HMA(N) = WMA(2*WMA(N/2) - WMA(N), sqrt(N))`. Popular in retail TA tooling, no clear evidence of superiority over EMA in crypto backtests. Not implemented in our stack.
- **TEMA (Triple EMA).** `3*EMA - 3*EMA(EMA) + EMA(EMA(EMA))`. Even lower lag than EMA, but more noise-sensitive. Not implemented.

The fundamental tradeoff is universal: less lag means more whipsaws. There is no smoothing technique that gets you "both" — what you save in latency you pay in false-cross signals on choppy data.

## The math, briefly

SMA's slope at time `i` is `(close[i] - close[i-N]) / N` — it depends only on the first and last close in the window, which is why SMA is so resistant to a single anomalous print but slow to register a regime change.

EMA's response to a step input (price jumps from A to B and stays) is geometric: after one period it covers α fraction of the gap, after two periods 2α-α², etc. The "effective window" of an EMA is approximately N. An EMA(20) reacts about as fast as SMA(20) on average but with a smoother trajectory.

For crypto on 1h or 4h timeframes, the practical difference between SMA20 and EMA20 in `TrendContinuationDetector`-style usage is small. We chose SMA for simplicity and because the trend-cross thresholds (`sma50 > sma200`) are inherited from the equity literature where SMA is canonical.

## When MAs work

- **Strong trending regimes.** SMA50 > SMA200 (the "golden cross" condition) is a clean filter for long-term uptrends. The `TrendContinuationDetector` uses exactly this. The cross itself isn't the entry; the *state* of being above-or-below filters out counter-trend setups.
- **Pullback-to-mean strategies.** Price extending too far above SMA20 is overbought relative to the local trend; a pullback to SMA20 in an uptrend is a higher-probability entry than a pullback into thin air. This is the structural rationale for our 0.3% - 2.0% pullback band against SMA20 in `TrendContinuationDetector`.
- **Multi-timeframe filtering.** A signal that disagrees across timeframes (long on 1h but the 4h SMA50/200 says downtrend) is statistically worse than one that agrees.

## When MAs fail

- **Choppy/ranging regimes.** In a CHOP regime classification (per `MarketRegimeService`), SMA crossovers fire repeatedly with no follow-through. The `signal-service` regime-aware thresholds explicitly account for this by tightening signal labels in BULL/BEAR and loosening in CHOP — but `TrendContinuationDetector` itself does not fire in chop because both directions require the long-term SMA50/SMA200 trend agreement, which absent in chop.
- **Around step-change events.** Coordinated regulatory announcements, ETF approvals, major listings — SMAs lag by their entire window length. A 200-day SMA does not register a regime break until ~100 days into the new regime.
- **On illiquid altcoins with sparse data.** SMA200 on a 1d chart needs 200+ days of clean history. Newer listings or post-delisting-then-relisting symbols produce nonsense values. The `Inputs.from` path in `TrendContinuationDetector` correctly returns `Optional.empty()` when any indicator is null.

## What we do today (in projectr-x)

`AnalyticsService` computes SMA20, SMA50, SMA200, EMA12, EMA26 on the 4h candle stream via [`IndicatorCalculator`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java). These flow as `analytics.technicalIndicators` into `MarketContext` for every symbol.

Consumed by:

- [`TrendContinuationDetector.resolveDirection`](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java) — uptrend = `sma50 > sma200 && price > sma50`, downtrend = opposite. This is the regime gate that allows the detector to fire at all.
- [`TrendContinuationDetector.buildSetup`](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java) — pullback measured as `abs(price - sma20) / price * 100`, must sit in `[MIN_PULLBACK_PCT=0.3%, MAX_PULLBACK_PCT=2.0%]` with the ideal at 0.5-1.5%.
- The `Technical` dimension score in `SignalEngine` includes MA-crossover contributions among its inputs.

We do not use EMA in either detector's direction decision. EMA12/EMA26 feed into MACD computation only (also via `IndicatorCalculator.calculateMACD`).

## Sources

1. [StockCharts ChartSchool — Moving Averages overview](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-overlays/moving-averages) — clean reference for SMA, EMA, and the SMA-vs-EMA visual comparison.
2. [Investopedia — Hull Moving Average](https://www.investopedia.com/articles/technical/06/elliott-wave-mass.asp) — definition and motivation for HMA. We don't use HMA but the article documents the lag-vs-smoothness math nicely.
3. Carver, *Systematic Trading* — Chapter on trend-following gives EMA-based signals their canonical statistical treatment. See `09-sources/01-books.md`.
4. Clenow, *Following the Trend* — Donchian and MA-based trend-following systems with detailed backtest methodology. See `09-sources/01-books.md`.
