# Mean Reversion

> Take the other side of an over-extended move. Profitable when extremes revert; catastrophic when they don't.

## Definition

Mean reversion is the assumption that prices oscillate around a slow-moving fair value and that excursions far from that anchor are statistically more likely to revert than to extend. The trader fades extremes: short above the upper band, long below the lower band, exit toward the centre.

Operationally, three families dominate retail and quant practice:

1. **Bollinger Band snap-back.** Bollinger (1980s) wrapped a 20-period SMA in ±2 standard deviations. The naive rule is "short the upper band, long the lower"; the practitioner refinement is "fade the band only when momentum is decelerating" (e.g., RSI divergence) and only inside a range regime.
2. **RSI extreme reversal.** Wilder's RSI<30 / RSI>70 thresholds are the most-misused indicator on retail charts. The honest version is asset-specific (BTC's RSI rarely closes below 25 even in crashes) and conditional on the higher-timeframe trend — RSI<30 in a daily uptrend is a buy; in a daily downtrend it's a "be patient, lower lows coming" warning.
3. **Pairs trading on cointegrated series.** The classical Vidyamurthy (2004) recipe: find two series whose spread is stationary (Engle-Granger or Johansen test), enter when the spread crosses ±2σ of its rolling mean, exit at 0σ. Long the cheap leg, short the rich leg; the absolute direction of the market washes out.

The theoretical foundation is Ornstein-Uhlenbeck — a continuous-time mean-reverting process with closed-form half-life and stationary distribution. Real markets aren't pure OU; the parameter estimates drift with regime, which is the whole game.

## When it works

- **Range-bound regimes.** When BTC chops between two horizontal levels for weeks (e.g., the July-Sept 2023 range, parts of Q1 2024), fading the edges of the range is a high-edge trade because the alternative outcome (breakout) is statistically rare relative to retests.
- **Low macro vol.** Mean reversion outperforms when realised vol drops and ATR contracts — the boundaries of the band become predictive rather than continuously expanding.
- **Same-sector pairs.** ETH/BTC ratio, SOL/ETH, and similar majors-to-majors pairs maintain enough structural correlation that statistical-arbitrage spreads have reasonable half-lives in calm regimes (see `05-statistical-arbitrage.md`).
- **Intraday "noise" timeframes.** At 1m-5m horizons, microstructure pressure (limit-order replenishment, market-maker inventory rebalancing) produces genuine OU-like dynamics. Chan's *Algorithmic Trading* documents persistent intraday mean reversion in equity futures with half-lives of 5-30 minutes.
- **Forced selling / liquidations.** A liquidation cascade overshoots fair value mechanically — there's a separate detector for this (see `03-liquidity-sweep-and-reversal.md`), but it's a mean-reversion thesis at its core.

## When it fails

- **Trending regimes.** This is the dominant failure mode and the reason mean reversion has bad-tail risk: when a strong trend prints, fading every "extreme" RSI reading produces small wins until one signal catches the start of a multi-day move and the position blows through 5+ stops. Carver: *"Mean reversion strategies have a negative skew that is the mirror image of trend following's positive skew."*
- **Crypto trend regimes specifically.** BTC's RSI stayed >70 for most of Q4 2024 while price rallied 35%. Anyone short the upper Bollinger band in that window lost continuously. The same pattern recurs in altcoin parabolic phases.
- **Cointegration breaks.** Pairs that were stationary for 6 months can divorce permanently when one of the assets undergoes a fundamental change — exchange delistings, hard forks, regulatory enforcement actions, or a token's macro narrative shifting (ETH PoS transition vs ETH-classic).
- **Survivorship bias in backtests.** A universe of "majors that still exist today" is automatically biased toward pairs whose mean-reversion held. The pairs that genuinely diverged have been delisted and don't show up. Chan repeatedly warns to backtest on point-in-time universes.
- **Selection bias on the entry.** "RSI<30" is mean reversion only conditional on a regime. Unconditional RSI<30 in crypto is a continuation signal more often than not — it indicates capitulation that frequently precedes another leg down.
- **Half-life longer than your patience.** A pair with a 30-day half-life means a 2σ entry has a 50% chance of returning to 0σ in 30 days. If margin requirements or funding-rate cost exceed the expected payoff, the trade is unprofitable even when "correct".

## What we do today (in projectr-x)

**We do not run a dedicated mean-reversion detector.** The closest current behaviour is the `LiquiditySweepDetector` (see `03-liquidity-sweep-and-reversal.md`), which fires a reversal entry — but the thesis there is "manipulation wick into stop pools", not "price strayed N sigma from a mean". The geometry rhymes; the underlying model does not.

The `RSI_MIN = 35, RSI_MAX = 65` guard inside `TrendContinuationDetector` (`services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java`) is explicitly *anti*-mean-reversion: it refuses to take a trend-continuation entry when RSI is already at a panic extreme, because that's the regime where mean reversion has higher edge than continuation.

Why we haven't shipped a mean-reversion detector:

- Crypto's regime mix in our 14-day observation window leaned trending (BULL on the regime classifier, confirmed by the +0.118R per signal aggregate edge on our trend-leaning detectors).
- Cointegration tests against our 13-symbol universe have not been formalised — without point-in-time cointegration scoring, a mean-reversion pairs detector would be flying blind.
- The empirical literature is clear that mean reversion in crypto requires very-short horizons (1-15min bars) where infrastructure latency and Bybit's 0.11% round-trip taker fee eat most of the edge.

## Implementation sketch (if we ship one)

A Bollinger-band fade detector, deliberately constrained to range regimes:

- **Class**: `RangeFadeDetector implements TradeSetupDetector`.
- **Regime gate**: only fire when `MarketRegimeService.classify()` returns `CHOP` (defined in `services/signal-service/src/main/java/com/cryptoradar/signal/service/MarketRegimeService.java`). In BULL or BEAR, skip.
- **Trigger**: 4h close beyond ±2σ Bollinger band, with the next bar's close back inside the band ("rejected the extreme").
- **Confluence**: require Derivatives dimension *agreeing with the reversion direction* (e.g., funding flipped negative while shorting the upper band) — extreme positioning is the leading indicator we have for crowded one-sided trades.
- **Stop**: 0.5 × ATR beyond the wick high/low, with `MIN_RISK_PCT = 0.015` floor.
- **Target**: middle band (SMA20). R:R will be modest (1.5-2.5R) — accept that; mean reversion is supposed to have high hit rate, low avg R.
- **Trail**: tighter than trend — `TrailConfig(0.5, 0.25, 0.25, …)` to lock partial wins fast because the right tail is structurally smaller.
- **Exit**: time-based stop at 8 bars (32h) — if it hasn't reverted by then, the thesis is broken regardless of price.

A pairs detector would need its own service or a substantial expansion of `analytics-service` — cointegration tests on 13 × 13 - 13 = 156 ordered pairs every hour, with rolling Johansen rank tests and half-life estimates. Effort: ≥1 week, and unlikely to clear the cost-of-fees hurdle for our universe size.

## Sources

1. **Chan, E. (2013). *Algorithmic Trading: Winning Strategies and Their Rationale*. Wiley.** — Chapters 2-4 cover mean reversion of stationary time series, ETF pair trading, and explicit examples of cointegration tests with point-in-time backtests.
2. **Vidyamurthy, G. (2004). *Pairs Trading: Quantitative Methods and Analysis*. Wiley.** — Foundational text on cointegration-based pairs construction, including Engle-Granger and Johansen procedures.
3. **Bollinger, J. (2002). *Bollinger on Bollinger Bands*. McGraw-Hill.** — Practitioner reference for the band construction and the (often-ignored) rule that bands are envelopes around volatility, not buy/sell signals on their own.
4. **Wilder, J. W. (1978). *New Concepts in Technical Trading Systems*.** — Original RSI paper; useful mainly to see how thresholds (30/70) were chosen arbitrarily and how downstream practice has reified them.
5. **Pole, A. (2007). *Statistical Arbitrage: Algorithmic Trading Insights and Techniques*. Wiley.** — Practical coverage of when statarb mean-reversion works (and the regimes that break it). Excellent treatment of half-life as a tradability filter.
6. **Carver, R. (2015). *Systematic Trading*. Harriman House.** — Chapter 5 has the cleanest written explanation of the negative-skew payoff of mean-reversion and why it's the mirror of trend.
