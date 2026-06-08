# Bollinger Bands

> Standard-deviation envelope around a moving average. The bands themselves are descriptive, not predictive — their highest practical value is the **squeeze** signal: low-volatility contraction foreshadowing a volatility expansion.

## Definition

Bollinger Bands, introduced by John Bollinger in the 1980s and codified in his 2001 book *Bollinger on Bollinger Bands*, are three lines:

- **Middle band** — N-period SMA of closes. Default N=20.
- **Upper band** — `middle + K * stdDev(closes, N)`. Default K=2.
- **Lower band** — `middle - K * stdDev(closes, N)`. Default K=2.

Implemented in [`IndicatorCalculator.calculateBollingerBands`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) with configurable period and K.

The 2σ default places ~95% of returns inside the bands under a normal-distribution assumption. Returns in crypto are not remotely normal — fat-tailed, asymmetric, vol-clustered — so the 95% capture rate is an idealization. Empirically on crypto 4h data, ~85-90% of bars close inside the 2σ bands, with the discrepancy concentrated in vol-expansion regimes.

## The squeeze

The single most useful Bollinger signal in retail TA is the **Squeeze**: the bands narrow to a multi-month low, then expand. Bollinger himself called this the foundation of his "Method I" volatility-breakout system in Chapter 16 of his book ([Bollinger on Bollinger Bands](https://www.amazon.com/Bollinger-Bands-John/dp/0071373683); [StockCharts — Bollinger Band Squeeze](https://chartschool.stockcharts.com/table-of-contents/trading-strategies-and-models/trading-strategies/bollinger-band-squeeze)).

The mechanism: realized volatility is mean-reverting. Long periods of low vol are *almost always* followed by periods of high vol. The squeeze doesn't tell you the direction of the eventual move — it tells you a directional move is coming, and to be ready to take it in whichever direction the breakout resolves.

Operationalization typically uses **Bollinger Bandwidth** (`(upper - lower) / middle`) — a normalized width measure. A 6-month low in Bandwidth triggers "squeeze on" state; the subsequent first close outside the bands is the entry.

## Keltner overlay — the "TTM Squeeze"

A common practitioner refinement (John Carter, *Mastering the Trade*, 2005) overlays Keltner Channels (ATR-based, see `03-atr-and-volatility.md`) on top of Bollinger Bands. When the Bollinger Bands sit *inside* the Keltner Channels — meaning standard-deviation volatility is below ATR-based volatility — the squeeze condition is "on." This produces a binary squeeze indicator (TTM Squeeze) more rigorous than eyeballing Bandwidth.

Not implemented in our stack, but the math is trivial if needed: both Bollinger and Keltner pieces are already computable via the analytics indicator suite (Keltner = EMA20 ± 2×ATR(20)).

## When Bollinger Bands work

- **Squeeze breakouts in major coins.** BTC and ETH on the daily timeframe have produced multiple textbook squeeze breakouts at cycle turns; documented in many post-hoc Bollinger analyses.
- **Mean-reversion in chop.** When realized vol is stable and price oscillates between extremes, fading the upper band and buying the lower band can work — *only* in confirmed chop regimes, and only with tight risk management. The MarketRegimeService CHOP regime would be the natural gate, though we don't currently couple Bollinger to detector logic.
- **As a regime visualization.** Bandwidth time-series is one of the cleanest visual representations of "is this market trending or ranging" — wide bands trending in one direction = trending, narrow stable bands = chop.

## When Bollinger Bands fail

- **Walking the bands.** In strong trends, price can "walk" the upper (or lower) band for many bars without mean-reverting. Fading the band touch is a structural loser in trends. Bollinger himself emphasizes this in the book — the bands describe volatility, they do not signal reversal.
- **Around regime breaks.** SMA20 is the centerline; in a sudden regime change, SMA20 takes 20 bars to incorporate the new regime, and the bands are slow to follow.
- **At very short periods.** Bollinger(5) is unstable. Bollinger(20) on 4h or daily is the load-bearing default for a reason.
- **For low-volume altcoins.** Volatility is dominated by single-print outliers; stdDev is noisy; bands flap.

## What we do today (in projectr-x)

[`IndicatorCalculator.calculateBollingerBands(closes, period, stdDevMultiplier)`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) computes upper/middle/lower bands and exposes them through `AnalyticsService` and `MarketContext.analytics().get("technicalIndicators")`.

**Neither detector uses Bollinger Bands as a gate.** The TrendContinuationDetector uses SMA50/SMA200 trend + RSI + pullback-to-SMA20; the LiquiditySweepDetector uses pierce-then-reclaim on swing-high/low with ATR-scaled thresholds. Bollinger contributes only to the `Technical` dimension score in SignalEngine via standard-deviation-based volatility weighting.

The forward-looking opportunity here is to add Bandwidth as a regime input — feeding `MarketRegimeService` a Bandwidth-low signal alongside the existing 50d-SMA/7-day slope criteria. A squeeze-active state would be a leading indicator of regime transition. Not currently coded.

## Sources

1. Bollinger, *Bollinger on Bollinger Bands* (2001) — author's own definitive treatment. The squeeze is Chapter 15-16. See `09-sources/01-books.md`.
2. [StockCharts ChartSchool — Bollinger Band Squeeze](https://chartschool.stockcharts.com/table-of-contents/trading-strategies-and-models/trading-strategies/bollinger-band-squeeze) — operational definition of the squeeze with examples.
3. [BollingerBands.com (John Bollinger's site)](https://www.bollingerbands.com/) — official reference materials including the 22 rules of Bollinger.
4. [Investopedia — Bollinger Bands](https://www.investopedia.com/terms/b/bollingerbands.asp) — accessible overview, useful for onboarding.
5. Carter, *Mastering the Trade* (2005) — TTM Squeeze definition combining Bollinger with Keltner overlay.
6. [TradingSim — 6 Bollinger Bands Strategies](https://www.tradingsim.com/blog/bollinger-bands) — practitioner-level examples; useful sanity check on what retail traders actually do with the indicator.
