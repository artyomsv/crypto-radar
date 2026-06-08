# Trend Following

> Buy what is going up, sell what is going down, and let the position run until the trend visibly breaks. Profit lives in the right tail; losses are small and frequent.

## Definition

Trend following is the systematic exploitation of price momentum at horizons of days to months. The trader makes no prediction about a "fair value"; they observe that an asset is moving in one direction, take a position with the move, and exit when the move ends. The canonical academic decomposition is that trend systems harvest a *positive serial correlation in returns over weeks-to-months* across nearly every liquid asset class — see Moskowitz, Ooi & Pedersen's "Time Series Momentum" (2012).

Entry rules cluster around three families:

1. **Channel breakouts** — Donchian's N-day high/low. Buy when price prints a new N-day high, exit when it prints a new shorter-period low. Richard Dennis's Turtles traded the 20-day / 55-day Donchian system on commodities and FX in the 1980s; modern CTAs use longer lookbacks (60-200 days) tuned to the asset's vol regime.
2. **Moving-average crosses** — long when fast MA (50-day) is above slow MA (200-day), flat or short otherwise. Variants include the "golden cross / death cross" of S&P lore, and AHL/MAN AHL's exponentially-weighted multi-horizon ensemble.
3. **Volatility-scaled momentum** — Clenow's "plunger" approach: take a position when price clears a long-horizon high by a multiple of ATR, size inversely to recent ATR, exit on a trailing ATR-band stop. Sizing makes a 60% vol altcoin and a 15% vol BTC contribute equal risk to the portfolio.

The empirical edge across all three is well-documented in equities, FX, commodities, and bonds — the AQR "Century of Evidence" paper (Hurst, Ooi, Pedersen, 2017) reproduces trend returns back to 1880. Crypto adds a fresh sample where the same factor has worked since 2013 with much higher gross R per unit horizon, partially offset by higher transaction costs.

## When it works

- **Sustained directional regimes.** Markets where information diffuses slowly (institutional accumulation, persistent macro narratives, regulatory cycles) create multi-week trends that absorb noise and let trail stops climb.
- **Heterogeneous beliefs / underreaction.** Behavioural finance attributes the momentum anomaly to traders being slow to update on news — see Hong & Stein (1999) "A Unified Theory of Underreaction, Momentum Trading, and Overreaction".
- **Crypto bull and bear cycles.** BTC has produced multi-month uptrends (Oct-Dec 2017, Mar-Apr 2021, Oct-Dec 2024) and equally clean downtrends (Q2 2022) where any reasonable trend filter caught the bulk of the move.
- **Liquid majors.** BTC and ETH carry deep enough order books that a CTA-style trail stop fires at intended levels rather than getting wicked out by thin-book noise.
- **Diversified application.** Trend works because it's repeated across many uncorrelated markets — the per-market hit rate is 40-45%, the portfolio Sharpe lives in the cross-section.

## When it fails

- **Chop and mean-reverting regimes.** Sideways markets stop a trend system out repeatedly. AQR's data shows trend's worst drawdowns coincide with persistent low-vol grinds (e.g., S&P 2017, BTC summer 2023). Carver in *Systematic Trading* explicitly warns: "trend following loses money about half the time and has long drawdowns; if you cannot tolerate 12+ months underwater, do not run it."
- **Whipsaw reversals.** A breakout that immediately fails (e.g., the BTC May 2021 top, the SOL Nov 2022 collapse) hits the stop and then reverses past the entry — pure losses with no compensating winner.
- **Crowded entries.** When a trend signal becomes consensus and large CTAs all enter at the same channel break, slippage compresses the realised edge and stops cluster, producing forced unwinds (see the Feb 2018 "vol-mageddon" CTA unwind documented in JP Morgan's 2018 flow notes).
- **Range-bound altcoins.** Mid-cap altcoins with thin order books range for months between obvious S/R; a Donchian channel trader collects death by a thousand cuts.
- **Regime shifts in correlation.** Crypto correlations spike toward 1 in stress events — a trend portfolio that thought it held 13 independent positions discovers it holds one BTC-beta position when something breaks.

## What we do today (in projectr-x)

The `TrendContinuationDetector` (`services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java`) is our concrete trend implementation. It is **not** a Donchian breakout system — it explicitly waits for an established trend and a healthy pullback rather than chasing the breakout itself, which trades fewer signals at higher specificity.

Rules, lifted directly from the source:

- **Trend gate**: `SMA50 > SMA200 AND price > SMA50` (LONG) or the mirror for SHORT. Daily timeframe.
- **Pullback band**: `0.3% ≤ |price − SMA20| / price ≤ 2.0%`. Ideal band is 0.5-1.5%; signals in the ideal band get a +5 alignment bonus.
- **RSI guard**: `35 ≤ RSI14 ≤ 65` — refuses to buy in panic and refuses to short into euphoria.
- **Confluence**: Technical dimension ≥ 20 in trade direction; Derivatives and Whale dimensions must not oppose by more than 15 and 20 points respectively.
- **Stop**: `1.5 × ATR14` below entry (above for shorts).
- **Target**: `max(entry + 5R, structural resistance)` — defers to structure when the resistance level is farther than the 5R minimum.
- **Trail**: `TrailConfig(1.0R activation, 0.5R step, 0.75R offset, 2.5R wider-activation, 1.0R wider-offset)` — the trend detector uses a deliberately wider trail (0.75R vs the 0.5R default) because Phase 4 outcomes showed 124 trail wins averaging +0.88R and TARGET hits averaging 11.79% MFE. The right tail is real, and a tight trail clips it. See `shared-trade-core/src/main/java/com/cryptoradar/core/TrailConfig.java` for the two-rung mechanics.

Execution-side, this detector is currently gated by `DetectorConfluenceCheck` in `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/DetectorConfluenceCheck.java`: a trend-continuation entry only dispatches to Bybit when an open `dimension-scoring` outcome exists in the same symbol+direction within 15 minutes. This is the v4 confluence rule that mirrored Vector B's LONG-only gate to SHORT after the first 4 v4 SHORTs all lost.

The deployment markers `v2-trail-system` (2026-04-19) and `v4-data-driven-vectors` (2026-04-24) document the operational tuning history.

## Implementation sketch (additions)

A Donchian breakout detector would be a useful complement (we don't have one). Rough shape:

- **Class**: `DonchianBreakoutDetector implements TradeSetupDetector` in the same `detector/` package.
- **Inputs**: highest high and lowest low of the prior N closed daily bars (N ≈ 40 for crypto majors based on Clenow's mid-term parameters; could be ATR-scaled).
- **Trigger**: closing price strictly greater than prior-N high (LONG) or less than prior-N low (SHORT). Use closing price, not intrabar — intrabar breakouts have far worse expectancy.
- **Stop**: `entry − 2.5 × ATR14`, with the `MIN_RISK_PCT = 0.015` floor inherited from `SignalEngine`.
- **Target**: `5R` minimum, defer to structural resistance.
- **Trail**: same `TC_TRAIL` config as trend-continuation, possibly with a 3R-activation rung to give breakouts more room before trail engages.
- **Filters**: skip when `ATR14 / price < 0.005` (range-bound regime); require Technical dimension agreement ≥ 30.
- **Effort**: ~1 day to implement and unit-test alongside the existing `LiquiditySweepDetector` test scaffolding.

## Sources

1. **Clenow, A. (2013). *Following the Trend: Diversified Managed Futures Trading*. Wiley.** — Practitioner reference for the volatility-scaled multi-asset trend system; the "Clenow plunger" rules and the ATR-sizing math come from here.
2. **Moskowitz, T., Ooi, Y. H., & Pedersen, L. H. (2012). "Time Series Momentum." *Journal of Financial Economics*.** https://www.sciencedirect.com/science/article/abs/pii/S0304405X11002613 — The canonical academic decomposition of time-series momentum across 58 liquid instruments.
3. **Carver, R. (2015). *Systematic Trading: A unique new method for designing trading and investing systems*. Harriman House.** — Honest practitioner book on building trend systems including the failure modes and drawdown distribution; the "trend loses half the time" warning is from chapter 4.
4. **Hurst, B., Ooi, Y. H., & Pedersen, L. H. (2017). "A Century of Evidence on Trend-Following Investing." AQR Capital.** https://www.aqr.com/Insights/Research/Working-Paper/A-Century-of-Evidence-on-Trend-Following-Investing — 137-year backtest of a simple 1-3-12-month time-series momentum system across equities, bonds, commodities, currencies.
5. **Hong, H., & Stein, J. C. (1999). "A Unified Theory of Underreaction, Momentum Trading, and Overreaction in Asset Markets." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/0022-1082.00184 — Behavioural mechanism: slow information diffusion produces the momentum anomaly.
6. **MAN AHL — "Trend Following: Why Now? A Macro Perspective." (multiple notes).** https://www.man.com/maninstitute — Practitioner perspective on when trend regimes turn on and off.
