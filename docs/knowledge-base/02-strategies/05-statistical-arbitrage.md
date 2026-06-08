# Statistical Arbitrage

> Find two (or more) assets whose price spread is statistically stationary, trade the deviations, hope the cointegration holds long enough to harvest the mean reversion.

## Definition

Statistical arbitrage ("statarb") is the broad family of strategies that exploit short-term, statistically-detectable mispricings between related assets. The classical pairs version proceeds in four steps:

1. **Universe + candidate generation.** All pairs (or triples) of assets in a defined universe — historically equities within a sector, in crypto we'd have BTC-ETH, ETH-BNB, BTC-BNB, etc.
2. **Cointegration test.** For each pair, regress one log-price on the other and test the residuals for stationarity. The two canonical tests are Engle-Granger (two-step OLS + ADF on residuals) and Johansen (joint VECM rank test). A passing pair has a spread that mean-reverts even though both series are individually non-stationary.
3. **Spread construction and signal.** Build the spread as `log(P_A) − β × log(P_B)` (β from the cointegration regression). Compute its rolling mean and standard deviation. Enter when the spread crosses ±2σ; exit at 0σ.
4. **Risk management.** Stop out when the spread crosses ±3-4σ (the cointegration is breaking) or when the half-life implied by the OU fit exceeds the holding-period tolerance.

The theoretical foundation is the Engle-Granger representation theorem (1987) — if two I(1) series are cointegrated, there exists a linear combination that is I(0), and a vector error-correction model captures the dynamics of return-to-equilibrium.

Statarb scales up: industry implementations like Gerry Bamberger's original 1980s Morgan Stanley pairs book, and later Renaissance/D.E. Shaw's dynamic dollar-neutral portfolios, run hundreds or thousands of relationships simultaneously and trade each one for fractions of a basis point of edge. The strategy's golden age was 1990-2002; the Avellaneda-Lee (2010) paper documents the gradual decay of US equity statarb returns as the strategy became crowded.

## When it works

- **Genuinely stationary spreads.** Same-sector US equities (KO/PEP, MA/V, JPM/BAC) maintained cointegration for years and were the bread-and-butter of equity statarb in its heyday.
- **Forced economic linkage.** Dual-listed stocks (Royal Dutch / Shell pre-merger), ADRs vs ordinaries, ETFs vs basket. Here the cointegration is mechanical (arbitrage in the underlying) and breaks only when capital constraints prevent the arb (LTCM 1998 is the canonical breakdown).
- **Short half-life with deep liquidity.** A pair whose spread mean-reverts in 1-5 days, in an asset pair you can size $millions in without market impact, is a printing press at moderate Sharpe.
- **High-frequency book-pressure arbitrage.** "Statarb" at HFT timescales — sub-second cointegration between top-of-book quotes of correlated assets — is a different beast and very profitable for participants with co-located infrastructure.

## When it fails

- **Cointegration breaks.** This is the defining failure mode and the entire reason statarb is hard. Two series can be stationary for 18 months then permanently diverge because one of them undergoes a fundamental change. Examples: KO/PEP after PEP's Frito-Lay synergy story shifted earnings; in crypto, ETH/BTC ratio after the Merge; SOL/ETH after Solana's outage-driven down-cycle in 2022 and meme-driven up-cycle in 2024. *A backtest that includes the broken-cointegration period and another that excludes it tells two completely different stories.*
- **Regime-dependent correlations in crypto.** Crypto correlations spike to ~0.95 in stress events. The pairs whose spreads were stationary in a calm regime see the same stress-driven dump — they don't behave like a market-neutral hedge; they behave like 2× leveraged BTC beta on the way down. Pole (2007) documents this generic statarb failure under regime shift.
- **Look-ahead bias on cointegration testing.** Using full-sample β coefficients is the most common backtest cheat. Real-world implementation requires rolling-window β estimates, which means the entry signal lags and the realised edge is much smaller than the in-sample backtest suggests.
- **Borrow / funding cost asymmetry.** Crypto short-side carry on a perp is the funding rate; if the spread persists for weeks, paying +1bps per 8h on a 1× short leg compounds to material drag.
- **Survivorship bias.** Backtesting today's "majors" (BTC, ETH, SOL, etc.) implicitly excludes the LUNA-USTs and FTT-style structurally-broken series whose spreads diverged catastrophically. Genuine point-in-time universe construction in crypto requires a delisting-aware data lake which is non-trivial to build.
- **Crowding and capacity.** Statarb capacity is finite — too many funds doing the same trade collapses the edge. Avellaneda & Lee (2010) document that US equity statarb Sharpe dropped from ~2.0 in the 1990s to ~0.5 in the 2000s; same fate likely waits for any well-known crypto pair.
- **Time-varying half-life.** OU-fit half-lives change with regime. A pair whose half-life was 5 days in calm vol can extend to 30+ days in turbulence, making the trade uneconomical (margin, funding, and patience all run out).

## What we do today (in projectr-x)

**We do not run any statarb strategy.** No cointegration testing, no pairs detector, no spread-construction service.

Rationale for the absence:

- Our universe is 13 USDT-perp pairs. The 13×12/2 = 78 unordered pairs is small enough to test exhaustively but most pair-relationships in crypto are dominated by BTC beta — the genuinely stationary residual would mostly be ETH/BTC, BNB/BTC, and a handful of others.
- Crypto pair cointegration in our 14-day observation window is fragile. The recent ETH/BTC ratio behaviour (long downtrend through 2023-24, sharp reversal in Q4 2024) is exactly the kind of regime shift that would have blown up a pairs book.
- Operationally, statarb requires *two* simultaneous orders on Bybit, two-leg margin management, and per-leg fee accounting. Our current `trade-execution-service` is single-leg only.

## Implementation sketch (if we ship statarb later)

A minimum-viable BTC-ETH pairs detector, mostly to learn the mechanics:

- **Class**: `BtcEthPairsDetector implements TradeSetupDetector` — though this doesn't fit cleanly because the detector contract is single-symbol. More likely a sibling: a `PairsService` with its own resource endpoints.
- **Data layer**: same TimescaleDB `candles` hypertable, hourly close prices for BTCUSDT and ETHUSDT over a 60-day window.
- **Estimation**: rolling 30-day OLS of `log(ETH) ~ log(BTC)` for the cointegrating vector β; ADF test on residuals; reject the pair if p > 0.05.
- **Signal**: enter when standardised residual crosses ±2.0; exit at 0; stop at ±3.5 or when ADF p-value > 0.10 (cointegration broke).
- **Sizing**: dollar-neutral on the two legs (`size_eth × eth_price = size_btc × btc_price`), with both legs sized within the daily-PnL budget the `GuardrailPolicy` allows.
- **Execution**: two orders submitted within ~100ms of each other on Bybit V5. Leg-failure handling is non-trivial — a partial fill on one leg becomes a directional bet, which is exactly what statarb is supposed to avoid.
- **Effort**: ≥2 weeks. Most of the work is in two-leg execution + reconciliation, not the statistics.

The first sensible milestone before building this: **measure cointegration stability of BTC-ETH** over our existing data and document whether the half-life is short enough to clear fees. If the empirical half-life is >7 days at current vol, statarb is unprofitable for us regardless of implementation polish.

## Sources

1. **Vidyamurthy, G. (2004). *Pairs Trading: Quantitative Methods and Analysis*. Wiley.** — The reference textbook for cointegration-based pairs construction. Engle-Granger, Johansen, and the practical considerations of β estimation are all here.
2. **Pole, A. (2007). *Statistical Arbitrage: Algorithmic Trading Insights and Techniques*. Wiley.** — Honest practitioner book on what kills statarb in real markets: regime breaks, capacity decay, time-varying half-life.
3. **Avellaneda, M., & Lee, J.-H. (2010). "Statistical Arbitrage in the US Equities Market." *Quantitative Finance*.** https://www.tandfonline.com/doi/abs/10.1080/14697680903124632 — Documents the empirical decay of US equity statarb Sharpe from ~2.0 (1990s) to ~0.5 (2000s) as the strategy became crowded.
4. **Engle, R. F., & Granger, C. W. J. (1987). "Co-integration and Error Correction: Representation, Estimation, and Testing." *Econometrica*.** https://www.jstor.org/stable/1913236 — Foundational econometrics paper; the two-step estimation procedure is named after them.
5. **Chan, E. (2013). *Algorithmic Trading: Winning Strategies and Their Rationale*. Wiley.** — Chapter 5 is a concise, code-oriented introduction to pairs trading with explicit out-of-sample tests on a real broker dataset.
6. **Krauss, C. (2017). "Statistical Arbitrage Pairs Trading Strategies: Review and Outlook." *Journal of Economic Surveys*.** https://onlinelibrary.wiley.com/doi/10.1111/joes.12153 — Recent literature review covering distance, cointegration, time-series, and stochastic-control approaches.
