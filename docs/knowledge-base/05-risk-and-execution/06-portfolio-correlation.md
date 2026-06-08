# Portfolio Correlation in Crypto

> When the panic comes, all 13 "diversified" alts go down together. Crypto-beta dominates the cross-section; the "alt diversification" of a 13-pair USDT-perp portfolio is largely an illusion under stress.

## Definition

In modern portfolio theory, **diversification benefit** scales with `1 − ρ̄`, where `ρ̄` is the average pairwise correlation of asset returns. Two assets with `ρ = 0` halve portfolio variance for a given expected return; two with `ρ = 1` give zero diversification.

In equities, well-constructed long-short portfolios across sectors run at `ρ̄` ~0.2–0.4 in normal markets. In crypto, the analogous numbers are dramatically worse. Liu, Marsh, Mazza & Petitjean's 2019 paper *Factor Structure in Cryptocurrency Returns and Volatility* (SSRN 3389152) studied nine liquid cryptocurrencies at high frequency and found that **a single common factor explains a very large fraction of cross-sectional volatility**, with Bitcoin acting as a "crypto market factor" whose realized betas with other coins *increase* during stress periods. The diversification effect, in other words, is exactly opposite to what you want: stable in calm markets, vanishing in crashes.

The post-2018 literature on crypto correlation (Didisheim, Fraschini & Somoza, 2022; Bhambhwani, Delikouras, Korniotis 2021; multiple arxiv papers) repeatedly finds:

- **Single dominant factor.** PCA on crypto returns typically loads 70–85% of variance onto the first principal component. The first PC is essentially "BTC ± a constant."
- **Stress-amplified correlation.** Realized 30-day correlation between top-20 alts and BTC rises to 0.85+ during sell-offs (Mar 2020, May 2021, Nov 2022 are the canonical examples).
- **Stablecoin de-pegs add tail risk.** USDT/USDC de-peg events (May 2022 UST collapse, Mar 2023 USDC SVB exposure) instantaneously correlate everything-vs-cash.

The implication for a 13-pair USDT-perp portfolio: under normal conditions, you have meaningful diversification across the alts. Under stress — exactly when diversification is supposed to save you — you have none. Your effective bet is "long-or-short the crypto market," dressed up as 13 separate trades.

## When it works

- **In calm regimes.** Average 30-day pairwise correlations of top-20 alts cluster around 0.5–0.7 in non-stress periods. That's still high but provides some diversification benefit — variance reduction of ~30–50% from holding multiple positions.
- **For market-neutral strategies.** Long-short pairs trading (long the strong relative, short the weak) explicitly targets the residual after the common factor. The dominant factor is what you hedge out.
- **Across asset categories.** BTC vs ETH vs SOL vs LDO is a within-crypto basket — high correlation. BTC + gold + bonds + cash is meaningful diversification across asset *classes*.

## When it fails

- **In stress regimes.** The exact moment a portfolio needs diversification, it doesn't have any. Mar 12, 2020 saw alts correlated to BTC at ~0.95+ for the duration of the crash.
- **In leveraged liquidation cascades.** Cascading liquidations on Bybit (or anywhere) push every leveraged position toward the liquidation engine simultaneously. Correlations spike to ~1 for the duration of the cascade, then revert.
- **Direction-symmetric portfolios.** A portfolio that's 5 LONG + 5 SHORT *looks* market-neutral but isn't — the LONGs and SHORTs are correlated *negatively to each other through the common factor*. In a market drop, all 5 LONGs lose AND all 5 SHORTs win, so net you might be fine — *but* this requires that your symbol selection was decorrelated from market direction in the first place. If your signals are themselves market-correlated (most signals are), the LONGs and SHORTs are not balanced when they fire and you can end up with a net long-or-short book during the worst moves.
- **Calling 13 positions "diversified."** 13 positions all in one venue (Bybit), all denominated in one quote (USDT), all subject to one regulatory event, all correlated by the same crypto-market factor — is diversified only by surface count.

## What we do today (in projectr-x)

The current portfolio risk model is **per-position and per-account**, not portfolio-aware:

- **Per-position cap:** `riskPerTradePercent` (default 1%) caps each trade's stop-out loss.
- **Concurrent positions cap:** `maxConcurrentPositions` (default 10) caps simultaneous open trades.
- **Account-level kill switch:** `DailyPnlCalculator` + `maxDailyLossPercent` (default 7%, 10% on account 297 DEMO) halts new entries when daily realized loss exceeds threshold.

**What's missing:** there is no explicit cap on net portfolio direction, no covariance-aware sizing, and no correlation-induced limit on concurrent exposure. If the engine fires LONGs on 10 symbols simultaneously in a strong BULL regime, all 10 get filled — at a 1% risk-per-trade cap, that's a 10% drawdown if every one of them stops out simultaneously. In a correlated sell-off, that *can* happen.

The implicit mitigations are:

1. **Regime conditioning.** `MarketRegimeService` raises SELL thresholds in BULL and raises BUY thresholds in BEAR. This makes the engine more conservative *into* the regime, reducing the likelihood of a 10-long pile-up during a fragile late-cycle bull.
2. **Detector diversity.** Different detectors (`LiquiditySweepDetector`, `TrendContinuationDetector`, dimension-scoring) fire on different signatures. A "10 LONGs all from dimension scoring" pile-up is more likely than "10 LONGs from 3 different detectors" — and the latter is what we'd prefer to see.
3. **The 10-position cap itself.** Even at worst case, 10 positions × 1% = 10% max risk. The 7% daily kill-switch trips before all 10 stop out.

But none of these is a **correlation-aware** risk budget. The empirical question — "what's the realized 1-day VaR of our current portfolio of open positions, given measured pair correlations?" — has no implementation. We are exposed to the failure mode the literature documents: silent correlation in calm periods, explicit dominance in stress.

Code references:
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/DailyPnlCalculator.java` — the only portfolio-level guardrail today.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/MarketRegimeService.java` — regime as a coarse proxy for correlation regime.
- `db/init/execution-init.sql` — `executed_trades` schema, the data we'd use for correlation telemetry.

## Implementation sketch (correlation-aware risk)

Three projects, in increasing order of effort:

1. **Net-direction cap.** Cap the absolute net direction (LONGs notional − SHORTs notional) at, say, 5% of equity. Effort: ~1 day in `OrderPlacer`. This alone prevents the "10 LONGs" pile-up scenario.
2. **Pairwise-correlation cluster cap.** Pre-compute 30-day rolling pairwise correlations across the 13 USDT pairs. Cluster symbols where `ρ > 0.7` into "correlation buckets." Cap concurrent positions per bucket (e.g. max 3 per bucket). Effort: ~3 days plus a continuously-running correlation refresh job.
3. **VaR-based portfolio risk budget.** Compute realized portfolio variance given current open positions, the rolling covariance matrix, and the position sizes. Cap new entries when 1-day 95th-percentile VaR exceeds, say, 3% of equity. This is the textbook implementation and is the most defensible. Effort: ~1 week. Requires `numpy`-style linear algebra, easiest in Python sidecar service.

Worth doing before the account scales meaningfully past the DEMO. At account sizes where 1% per trade = $100 per trade, the engineering is overkill. At account sizes where 1% per trade = $10,000+, the cost of *not* having portfolio risk math compounds with the size.

## Sources

1. [Liu, J., Marsh, I. W., Mazza, P., & Petitjean, M. "Factor Structure in Cryptocurrency Returns and Volatility" SSRN 3389152](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3389152) — high-frequency study of nine liquid cryptocurrencies. The "BTC as crypto market factor" finding and the bubble-period beta inflation are the load-bearing results for this doc.
2. [Didisheim, A., Fraschini, M., & Somoza, L. "The End of the Crypto-Diversification Myth" SSRN 4138159](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4138159) — direct attack on the "alts diversify" claim with post-2020 data. Confirms the stress-amplified correlation finding.
3. [Liu, Y. & Tsyvinski, A. "Risks and Returns of Cryptocurrency" *Review of Financial Studies* 34(6) 2689-2727 (2021)](https://academic.oup.com/rfs/article-abstract/34/6/2689/5912024) — foundational paper on the systematic factor structure in crypto returns. Original NBER working paper [w24877](https://www.nber.org/papers/w24877).
4. [arXiv 2501.09911 "Institutional Adoption and Correlation Dynamics: Bitcoin's Evolving Role in Financial Markets"](https://arxiv.org/pdf/2501.09911) — recent (2025) study on BTC's correlation with traditional assets and within-crypto factor structure.
5. [Bakry, W., Khaki, A. R., Al-Mohamad, S., & El-Kanj, N. "Bitcoin and Portfolio Diversification: A Portfolio Optimization Approach" SSRN 3614606](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3614606) — explicit diversification-benefit calculation under different correlation regimes.
6. [arXiv 2212.01267 "Understanding Cryptocoins Trends Correlations"](https://arxiv.org/pdf/2212.01267) — methodology paper on correlation estimation in high-frequency crypto returns, with the caveat that estimator choice matters under structural breaks.
7. [PMC 10232353 "Diversification evidence of bitcoin and gold from wavelet analysis"](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10232353/) — counter-evidence that BTC provides cross-asset (vs gold) diversification even when within-crypto diversification fails.
