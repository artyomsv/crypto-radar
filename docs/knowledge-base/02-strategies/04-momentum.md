# Momentum

> Past winners keep winning, past losers keep losing — over horizons of weeks to a year. The single most-replicated factor in empirical finance, alive and well in crypto.

## Definition

"Momentum" in the academic sense refers to two related but distinct phenomena:

1. **Cross-sectional momentum (XS)** — rank a universe of assets by trailing N-month returns; long the top decile, short the bottom decile, rebalance monthly. The strategy makes no directional bet on the market; it bets that the *relative* ordering of past returns predicts future relative ordering. Jegadeesh & Titman (1993) is the canonical equity-market result, robust at horizons of 3-12 months with reversal at 1 month and at 3-5 years.
2. **Time-series momentum (TSMOM)** — for each asset independently, go long if its trailing N-month return is positive, short if negative. The direction of the absolute move predicts the direction of the next move. Moskowitz, Ooi & Pedersen (2012) is the canonical paper across 58 instruments globally; the strategy survives transaction costs and is theoretically related to trend following (see `01-trend-following.md`).

The two are distinct factor exposures. TSMOM is implicitly long the market in bull regimes and short in bear regimes; XS is market-neutral. Both have positive Sharpe across decades of data, both have low correlation to value/quality/carry factors, both are funded by behavioural mechanisms (underreaction, slow information diffusion, herding) rather than risk premia.

Asness, Moskowitz & Pedersen's (2013) "Value and Momentum Everywhere" demonstrated that the XS momentum effect is present in equities, country indices, currencies, government bonds, and commodities — and that a global XS-momentum portfolio earns ~6% annualised excess return with low correlation to standard factors.

## When it works

- **Multi-asset universes.** XS momentum benefits from diversification: ranking 13 crypto pairs against each other produces a portfolio whose long/short legs partially cancel idiosyncratic noise, leaving the persistent return-ordering signal.
- **Persistent macro narratives.** When BTC dominance is rising, large-caps systematically outperform small-caps for weeks; momentum ranking captures the rotation in real time without needing a macro forecast.
- **Crypto specifically.** Multiple peer-reviewed studies have replicated momentum in crypto: Liu & Tsyvinski (2021) "Risks and Returns of Cryptocurrency" finds a strong 1-week to 4-week cross-sectional momentum effect; the magnitude in crypto is larger than in equities (Sharpe ~1.0 unhedged vs ~0.5 in US equity momentum).
- **Higher fees but bigger edge.** Crypto's gross momentum is large enough to clear 5-10 bps round-trip costs at weekly rebalancing. At daily rebalancing on Bybit perps with 0.11% round-trip taker fees, the edge compresses rapidly — weekly or 2-weekly is the practical sweet spot.
- **Periods of dispersion.** Momentum works best when there's enough cross-sectional spread for the long/short decile to differ meaningfully. Q4 2021 (ETH outperforming BTC), Q4 2024 (memecoin season + L1 rotation) gave huge dispersion. Q3 2022 (correlated drawdown) gave very little.

## When it fails

- **Momentum crashes.** The single biggest known failure mode: after a regime change (bear-to-bull), the recent "losers" (which momentum is short) snap back violently, producing enormous losses on the short leg. Daniel & Moskowitz (2016) "Momentum Crashes" documents this in equities — the 2009 March recovery cost a long-short momentum portfolio ~80% of its equity in 3 months. Crypto's equivalents include Mar 2020 (post-COVID-crash bounce), Jan 2023 (FTX-bottom bounce), Nov 2023 (Solana 5x).
- **Correlations spike to 1.** When everything drops together, the long-decile and short-decile both lose. The market-neutral hedge fails because the cross-section has no information left to extract.
- **Short-side cost in crypto.** Borrow cost (funding rate) for shorting a momentum-low altcoin can swing to +0.5%/day in a meme season. Even if the directional thesis is right, the funding bleed eats the realised return.
- **High dispersion + thin liquidity.** A momentum decile that ranks a delisted-trajectory coin highly because of a recent dead-cat bounce will hit slippage worse than any backtest captures. Crypto has a long tail of zombie tokens whose theoretical "momentum" rank is unactionable.
- **Look-ahead bias on universe construction.** Backtesting on "top 100 by market cap today" automatically excludes the dozens of coins that mooned, dumped, and delisted in the period. Point-in-time market-cap universe construction is mandatory; this is harder than it sounds.
- **TSMOM in chop.** Single-asset time-series momentum gets whipsawed in low-volatility, range-bound regimes. The signal has no information when the asset is mean-reverting around a flat trend.

## What we do today (in projectr-x)

We do not currently run a dedicated cross-sectional or time-series momentum *detector* in the explicit Jegadeesh-Titman sense. The closest implementations:

- **`TrendContinuationDetector`** (`services/signal-service/src/main/java/com/cryptoradar/signal/detector/TrendContinuationDetector.java`) is a per-symbol time-series momentum filter — it goes long when `SMA50 > SMA200 AND price > SMA50` and looks for a pullback entry. This is a TSMOM rule with an entry-timing overlay. See `01-trend-following.md` for the full mechanics.
- **The Technical dimension scorer** inside `SignalEngine` aggregates RSI, MACD, MA slope, ROC and similar momentum-flavoured indicators into a per-symbol score that feeds the overall signal. This is a per-symbol momentum signal in proxy form, not a ranked cross-sectional one.

We do **not** rank our 13 USDT-perpetual pairs against each other for a long-short overlay. That would be the obvious place to add a cross-sectional momentum signal.

## Implementation sketch (cross-sectional momentum)

A weekly-rebalanced cross-sectional momentum signal as an additional dimension:

- **New service or module**: `XSMomentumScorer` inside `signal-service`. Reads all 13 symbols' close prices from the shared `candles` hypertable (same access pattern as `RealizedVolService` in `options-service` — see `services/options-service/src/main/java/com/cryptoradar/options/service/RealizedVolService.java` for the native-query pattern).
- **Lookback**: trailing 28-day return per symbol (skip the most recent 7 days to avoid the 1-week reversal, per Jegadeesh-Titman convention).
- **Output**: per-symbol Z-score against the universe mean. Z > +1 → bullish XS momentum, Z < −1 → bearish.
- **Integration**: feed the Z-score into `MarketContext` as a new dimension (`"XS Momentum"`) with same +100/-100 scaling as the other dimensions. The `SignalEngine` already weights and combines dimensions, so this would surface naturally in the overall score without needing engine surgery.
- **Rebalance cadence**: weekly. Don't recompute every minute — the signal half-life is days, not seconds.
- **Effort**: ~2 days including the lookback skip-window debugging and adding a Phase-N deployment marker to slice metrics before/after.

A long-short portfolio overlay on top of this (long top 3 by Z, short bottom 3) would be a different system — it would belong in `trade-execution-service` and would interact with the per-symbol `SymbolPerformanceGate` in non-trivial ways. Worth doing later, but not before measuring the dimension-level signal first.

## Sources

1. **Jegadeesh, N., & Titman, S. (1993). "Returns to Buying Winners and Selling Losers: Implications for Stock Market Efficiency." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1540-6261.1993.tb04702.x — Original cross-sectional momentum paper. 3-12 month sort, monthly rebalance, robust to specification.
2. **Moskowitz, T., Ooi, Y. H., & Pedersen, L. H. (2012). "Time Series Momentum." *Journal of Financial Economics*.** https://www.sciencedirect.com/science/article/abs/pii/S0304405X11002613 — TSMOM across 58 global instruments; the foundation for modern CTA-style strategies.
3. **Asness, C. S., Moskowitz, T. J., & Pedersen, L. H. (2013). "Value and Momentum Everywhere." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/jofi.12021 — Demonstrates the momentum effect is present in equities, indices, FX, bonds, and commodities — and that value and momentum are negatively correlated across asset classes.
4. **Daniel, K., & Moskowitz, T. J. (2016). "Momentum Crashes." *Journal of Financial Economics*.** https://www.sciencedirect.com/science/article/abs/pii/S0304405X16301301 — Documents the asymmetric tail risk of long-short momentum after regime reversals.
5. **Liu, Y., & Tsyvinski, A. (2021). "Risks and Returns of Cryptocurrency." *Review of Financial Studies*.** https://academic.oup.com/rfs/article/34/6/2689/5917119 — Empirical evidence for the momentum factor in crypto specifically (1-4 week horizon, Sharpe ~1.0).
6. **Asness, C. S., Frazzini, A., Israel, R., & Moskowitz, T. J. (2014). "Fact, Fiction, and Momentum Investing." *Journal of Portfolio Management*.** https://jpm.pm-research.com/content/40/5/75 — Practitioner-facing rebuttal to common criticisms of momentum (transaction costs, capacity, robustness).
