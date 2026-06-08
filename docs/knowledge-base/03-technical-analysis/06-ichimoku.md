# Ichimoku Kinko Hyo

> Japanese all-in-one indicator package: trend direction, momentum, and forward-projected support/resistance in a single chart overlay. Popular in crypto because it gives a quick visual read of an unfamiliar symbol, but its five lines together do not add reliable edge beyond what simpler MA/momentum combos provide.

## Definition

Ichimoku Kinko Hyo ("one glance equilibrium chart") was developed by Goichi Hosoda in pre-WWII Japan and published in 1969. Five components computed from the high-low midpoint over different windows:

- **Tenkan-sen (Conversion Line).** `(highest_high(9) + lowest_low(9)) / 2`. Fast trend indicator.
- **Kijun-sen (Base Line).** `(highest_high(26) + lowest_low(26)) / 2`. Medium-term trend.
- **Senkou Span A (Leading Span A).** `(Tenkan + Kijun) / 2`, shifted 26 bars forward. Forms one edge of the cloud.
- **Senkou Span B (Leading Span B).** `(highest_high(52) + lowest_low(52)) / 2`, shifted 26 bars forward. The other cloud edge.
- **Chikou Span (Lagging Span).** Current close shifted 26 bars *backward*. Compared against historical price for trend confirmation.

The **Kumo (cloud)** is the area between Span A and Span B. Above-cloud = uptrend; below-cloud = downtrend; inside-cloud = chop. The cloud has the unique property of being *forward-projected* — it visualizes future S/R zones, which is unusual among indicators.

## Why it's popular in crypto specifically

Three reasons crypto traders gravitate to Ichimoku more than equities traders do:

1. **Visual immediacy.** A trader can glance at any unfamiliar symbol's chart and read the regime instantly — above/below cloud, cloud thickness, Tenkan/Kijun cross. This matters in crypto's 1000+ tradeable pairs where you can't carry mental models for each.
2. **24/7 markets need state indicators.** Equities have sessions, weekends, opens, closes — natural reset points. Crypto runs continuously. An indicator that maps any moment to a regime label is useful precisely because there's no natural session structure.
3. **The cloud's forward projection feels predictive.** Other indicators are reactive; Ichimoku draws lines into the future, which appeals to traders who want anticipation rather than confirmation.

The visual appeal is also a trap: the indicator looks *more* informative than simpler alternatives without being measurably better in backtests. Comparative studies (mostly in retail-TA literature, sparse academic coverage) find that Ichimoku-based systems produce returns roughly comparable to SMA-crossover systems at the same parameters, with no statistical separation. ([Changelly — Ichimoku for Crypto](https://changelly.com/blog/ichimoku-cloud-for-crypto-trading/); [BingX — Ichimoku Strategy in Crypto](https://bingx.com/en/learn/article/what-is-ichimoku-cloud-strategy-how-to-use-in-crypto-trading))

## The standard signals

- **Tenkan-Kijun cross** (TK cross). Tenkan crossing above Kijun while both are above the cloud = strong buy. The reverse below the cloud = strong sell. A "weak" cross is inside or against the cloud.
- **Price-cloud breakout.** Price closing above the cloud after sitting inside is a trend-change confirmation. Reverse for downside.
- **Chikou Span confirmation.** Chikou above the price 26 bars ago confirms an uptrend. Below confirms a downtrend.
- **Cloud thickness.** Thick cloud = strong S/R; thin cloud = weak S/R. Cloud flips (Span A crossing Span B) often coincide with significant trend changes.

A "full" Ichimoku buy signal requires all four conditions to align. This produces specificity (very few false positives) at the cost of sensitivity (you miss many real moves waiting for the full setup).

## When Ichimoku works

- **Strong trending crypto markets on the daily/4h timeframe.** Above-cloud uptrends in BTC during the late phases of bull cycles produce clean, sustained Ichimoku buy signals.
- **As a regime filter.** "Don't take long signals when price is below the cloud" is a useful additive filter on top of whatever entry logic you use. This is closer to how Ichimoku is profitably deployed in systematic stacks — as a gate, not as the entry signal itself.
- **Multi-timeframe alignment.** When the daily and 4h Ichimoku regimes agree, the trade is higher-quality than when they disagree.

## When Ichimoku fails

- **Choppy markets.** Price oscillating through a thin cloud produces a stream of TK crosses, each invalidated within a few bars. Ichimoku is famously bad in chop.
- **Around regime breaks.** Cloud projections are based on past 9/26/52-bar data. When the regime changes suddenly (a major news shock, an exchange event), the cloud points to "the past's future" and is irrelevant for several days until the windows roll.
- **On low-volume altcoins.** Highest-high / lowest-low over 52 bars is extremely sensitive to single-bar extremes. A thin-book wick on bar 1 distorts the cloud for the next 26 bars.
- **As a primary entry signal in isolation.** The 4-condition full setup is rare and often coincides with the *middle* of a move, not its start.

## What we do today (in projectr-x)

**Ichimoku is not computed and not used in either detector or any dimension scorer.** Our trend-state logic uses SMA50/SMA200 instead, which is mechanically similar (the Kijun-sen is essentially `(HH26 + LL26) / 2`, comparable to a 26-period donchian midline — different math but the same "where has this symbol been over the last N bars" intuition).

We chose SMA over Ichimoku because:

1. SMA50/SMA200 are universally recognized across both equity and crypto literature; backtests and parameter studies are abundant.
2. Adding 5 more lines of Ichimoku state to `MarketContext.analytics()` increases UI complexity without measurable signal improvement on our specific use case.
3. The forward-projected cloud's "anticipation" appeal isn't compatible with our execution-driven approach, which uses *current* state, not projected state.

If we ever want to test Ichimoku, the implementation path is clear: a new method `IndicatorCalculator.calculateIchimoku(...)` returning `{tenkan, kijun, spanA, spanB, chikou}`, surfaced via `AnalyticsService` into the existing `analytics.technicalIndicators` map, optionally used as a regime filter in `MarketRegimeService` (or its replacement). Effort: ~half a day. Backtest budget: separate exercise, expected to show no significant lift over the current SMA-based regime classifier.

## Sources

1. [Avatrade — Ichimoku Cloud Indicator & Strategies](https://www.avatrade.com/education/technical-analysis-indicators-strategies/ichimoku-cloud-indicator-strategies) — solid retail-oriented overview of the 5 lines and signal combinations.
2. [Changelly — Ichimoku Cloud for Crypto Trading](https://changelly.com/blog/ichimoku-cloud-for-crypto-trading/) — crypto-specific framing with sample setups.
3. [FxPro Glossary — Ichimoku Kinko Hyo](https://www.fxpro.com/help-section/traders-glossary/ichimoku-kinko-hyo) — clean definition with the original Hosoda publication context.
4. [BingX — Ichimoku Strategy in Crypto Trading](https://bingx.com/en/learn/article/what-is-ichimoku-cloud-strategy-how-to-use-in-crypto-trading) — operational examples on liquid crypto pairs.
5. [3Commas — Ichimoku Cloud Strategy Guide](https://3commas.io/blog/the-detailed-guide-to-using-ichimoku-cloud-strategy) — practitioner walkthrough including the multi-condition "full setup".
6. [TradingView — Ichimoku Cloud documentation](https://www.tradingview.com/support/solutions/43000502608-ichimoku-cloud/) — platform-standard implementation; useful for validation if we ever code it.
