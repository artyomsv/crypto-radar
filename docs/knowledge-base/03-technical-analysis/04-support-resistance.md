# Support and Resistance

> Price levels where supply or demand previously concentrated. Detected via swing-high / swing-low pivots, volume profile, or round-number psychology. Their value as predictive levels degrades each time price retests them.

## Definition

A **support** level is a price below the current market where buying pressure has historically absorbed selling, causing price to bounce. A **resistance** level is the symmetric concept above the market where selling has historically capped advances.

The mechanism is not mystical: at a previously tested low, three groups of traders have leftover orders or memory:

1. Sellers who exited at that low previously and now have it pinned as "the bottom" — likely to add longs there.
2. Buyers who missed the prior low and want a second chance.
3. Stops sitting just *below* the prior low, ready to fire if it breaks.

The level becomes self-reinforcing while it holds — and self-destructive once it breaks, because the third group's stops cascade through.

## Detection methods

- **Swing-high / swing-low pivots.** A bar whose high (or low) is the local extreme within a ±N-bar window. Standard N is 2-5 bars. Our implementation uses a 2-bar lookback in [`IndicatorCalculator.calculateSupportResistance`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java).
- **Volume profile / Volume by Price (VbP).** A histogram of volume traded *at each price level*, regardless of time. High-volume nodes are where most transactions happened — those prices "remember" more positioning than thinly-traded prices. Discussed in `05-volume-analysis.md`.
- **Round numbers.** $100,000 BTC, $5,000 ETH. Mostly self-fulfilling because traders set orders at round numbers. Real and visible on liquidity heat maps from venues like Bookmap.
- **Anchored VWAP from significant events.** A swing-low VWAP from a major bottom acts as dynamic support. Discussed in `08-vwap-and-anchored-vwap.md`.
- **Fibonacci retracement levels.** The 38.2%, 50%, 61.8% pullback levels of a prior swing. Popular and self-fulfilling in equity TA. Used heavily in crypto retail without strong empirical justification. Not in our stack.

## The math in our implementation

[`IndicatorCalculator.calculateSupportResistance`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java):

1. Walk the bars with a lookback of 2 on each side.
2. A bar is a swing low if `low[i] < low[i-1]` and `low[i] < low[i-2]` and `low[i] < low[i+1]` and `low[i] < low[i+2]`.
3. The **support** is the highest swing low that sits below the current close.
4. The **resistance** is the lowest swing high that sits above the current close.
5. Fallback (if no swing point found in the window): the min low / max high of the last 50 bars.

This is intentionally simple. More elaborate alternatives:
- N=3 or N=5 lookback for fewer, higher-quality pivots (we deliberately use N=2 for responsiveness on 4h candles).
- Cluster swing points within K% of each other and use the cluster centroid (better when multiple pivots line up).
- Weight by volume at the pivot — a swing low on heavy volume "counts" more than the same low on light volume.

The trade-off is that any of these refinements add false-positive risk (clustering merges levels that traders may treat distinctly) and code complexity. The simple version produces usable structural targets for our detectors.

## When S/R works

- **Mean-reverting setups.** Both `LiquiditySweepDetector` and `TrendContinuationDetector` use the support/resistance levels as **structural targets**: a LONG targets the next resistance above entry, a SHORT targets the next support below. The R-multiple target (5R extension) takes precedence only when it's farther than the structural target — otherwise the structural level caps the take-profit.
- **Re-test fails.** When price approaches a resistance, fails to break it, and reverses, that's a high-quality short setup. The LS detector encodes a related pattern: pierce-then-reclaim is a re-test fail signal.
- **Breakout confirmation.** Price closing decisively (>1 ATR) beyond a long-tested S/R level, on volume confluence, often signals a regime change. The LS detector's `MIN_VOLUME_RATIO = 1.3` against the prior 3 bars captures one half of this — the volume confirmation half.

## When S/R fails

- **Stale levels.** A resistance from 6 months ago doesn't have the same trader memory as one from last week. We do not currently age-weight pivots — `calculateSupportResistance` treats all swing points within its lookback window as equally relevant.
- **In strong trends.** Resistance levels in a strong uptrend get *eaten* — they pause price for a bar or two and then are sliced through. Trying to short into them is a "knife-catching" failure mode. The detectors avoid this case structurally: `TrendContinuationDetector` only goes long in uptrends; `LiquiditySweepDetector` requires explicit pierce-then-reclaim, which itself only fires when the level is holding.
- **Around major news.** S/R levels are erased instantly by significant fundamental news. Pre-FOMC or pre-CPI signals using structural targets need to factor in the calendar.
- **For ranges with multiple equal lows.** A double-bottom or triple-bottom support is genuinely supported — until it isn't, and then the cascade is large because every stop sits just under the same level.
- **Low-volume regimes.** S/R levels formed during low-volume sessions are weaker than those formed during high-volume sessions. Our current implementation doesn't filter by volume at the pivot.

## What we do today (in projectr-x)

[`IndicatorCalculator.calculateSupportResistance`](../../services/analytics-service/src/main/java/com/cryptoradar/analytics/service/IndicatorCalculator.java) computes `supportLevel` and `resistanceLevel` from the 4h kline series and exposes them via the `analytics` map on `MarketContext`. Detector consumption:

- [`TrendContinuationDetector.buildSetup`](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java): structural target = `resistance` for LONG, `support` for SHORT. Final target = `max(rMultipleTarget, structuralTarget)` for LONG (further-away wins), `min(...)` for SHORT.
- [`LiquiditySweepDetector.buildSetup`](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/LiquiditySweepDetector.java): swing high/low computed locally within the detector (not from `analytics`) over the most recent N-2 bars of the 4h window. This is intentional — the LS detector wants the swing that the trigger bar *swept*, which by construction is the freshest swing.

We do not currently surface a volume-profile-derived S/R in the analytics map. That's tracked as a forward-looking enhancement to refine target placement.

## Sources

1. [StockCharts ChartSchool — Support and Resistance](https://chartschool.stockcharts.com/table-of-contents/chart-analysis/support-and-resistance) — canonical retail-TA framing; useful as a baseline for the swing-point definition.
2. [Investopedia — Support and Resistance](https://www.investopedia.com/trading/support-and-resistance-basics/) — accessible primer; relevant to onboarding new contributors.
3. Murphy, *Technical Analysis of the Financial Markets* — Chapter 4 covers S/R formation in detail. Standard reference.
4. Bookmap docs / Volume Profile literature — heatmap-based S/R is the modern descendant of pivot detection; gives a sense of where the field is heading.
5. Carver, *Systematic Trading* — argues that pivot-based S/R adds little incremental edge over trend filters; useful counter-perspective. See `09-sources/01-books.md`.
