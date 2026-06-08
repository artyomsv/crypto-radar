# Papers — Annotated

> The peer-reviewed and arXiv literature that directly shapes how projectr-x evaluates signals, sizes trades, and structures backtests. Each entry says **what we learned** — citation-mining for its own sake is not the point.

## Evaluation methodology

### Bailey & López de Prado — *The Deflated Sharpe Ratio* (2014)

**Citation.** Bailey, D. H. & López de Prado, M. (2014). *The Deflated Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting and Non-Normality.* Journal of Portfolio Management 40(5):94-107.

**URL.** <https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551>

**What we learned.** Sharpe ratios computed on the "winning" strategy from a backtest search are *systematically inflated* by selection bias. The Deflated Sharpe Ratio (DSR) corrects for both the number of trials and the non-normality of returns. If you tested 100 strategies and the best one has Sharpe 1.5, the *deflated* Sharpe might be 0.4 — not statistically distinguishable from zero. **This is the reason we maintain `deployment_markers` rather than recomputing aggregate Sharpe over the full history**: each marker bounds a coherent regime under which a *single* hypothesis was active, so the in-cohort Sharpe is closer to honest.

## Crypto factor structure

### Liu, Marsh, Mazza, Petitjean — *Factor Structure in Cryptocurrency Returns and Volatility* (2019)

**URL.** <https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3389152>

**What we learned.** Cryptocurrencies have a factor structure in both returns and volatility, but the common factor explains volatility much more strongly than returns. BTC is *not* a particularly strong common factor for the broader crypto market — which surprised the authors. The implication for us: scoring symbols against "BTC's direction" alone is missing the variance picture. The 6-dimension scorer in `SignalEngine` partially addresses this by giving non-BTC inputs (whale flows on each symbol, per-symbol derivatives data) equal footing.

### Li & Zhu — *A LASSO-Type Factor Model in Cryptocurrency* (2025)

**URL.** <https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4437594>

**What we learned.** Tests 49 candidate cryptocurrency anomalies (size, momentum, beta, illiquidity, etc.) using LASSO-type factor selection. Many anomalies are correlated, and a small number of factors (5-7) explain most of the cross-section of returns. **Implication for us**: when we eventually add a multi-symbol cross-sectional score, we shouldn't treat each candidate factor as independent — LASSO-style sparse selection is the right approach. We don't have this in `SignalEngine` yet; the 6 dimensions are independent by design.

## Trend-following and portfolio construction

### Asness, Moskowitz, Pedersen — *Value and Momentum Everywhere* (2013)

**Citation.** Asness, C. S., Moskowitz, T. J., & Pedersen, L. H. (2013). *Value and Momentum Everywhere.* Journal of Finance 68(3):929-985.

**URL.** <https://onlinelibrary.wiley.com/doi/10.1111/jofi.12021>

**What we learned.** Value and momentum work *across* asset classes, not just within them. Value-and-momentum factors correlate negatively *within and across* asset classes — so a value-and-momentum portfolio is naturally diversified. **Why we cite this**: it's the academic justification for taking trend-following seriously on crypto even though crypto isn't in the original sample. The crypto-specific replications (Liu 2019, Li 2025) confirm the structure holds.

### arXiv 2602.11708 — *Systematic Trend-Following with Adaptive Portfolio Construction* (Bui & Nguyen, Feb 2026)

**URL.** <https://arxiv.org/abs/2602.11708>

**What we learned.** Recent paper that explicitly addresses **the trailing-stop calibration problem** in crypto trend-following: the authors propose a "dynamic trailing stop mechanism calibrated to intra-day volatility regimes," an "asymmetric 70/30 long-short allocation grounded in empirical positive drift of crypto markets," and a "rolling Sharpe-ratio-based asset selection procedure." Backtested across 150+ pairs over 36 months they report Sharpe 2.41 and max drawdown -12.7%. **Implication for us**: validates the design choice of (a) regime-adaptive trail offsets (our 2.5R second-rung widening), (b) treating long bias as the default in crypto (the regime-aware thresholds in `SignalEngine.determineSignalLabel`), and (c) per-symbol Sharpe-tracking gates (our `SymbolPerformanceGate`). Independent confirmation rather than primary source.

## Microstructure and execution

### arXiv 2602.00776 — *Explainable Patterns in Cryptocurrency Microstructure* (Bieganowski & Ślepaczuk, Jan 2026)

**URL.** <https://arxiv.org/abs/2602.00776>

**What we learned.** Uses Binance Futures perpetual 1-second L2 data on BTC, LTC, ETC, ENJ, ROSE from 2022-2025. SHAP-based analysis shows order-book-imbalance features have **stable, transferable predictive importance** across assets spanning orders of magnitude in market cap. Validates tradability with a conservative top-of-book taker backtest — economically positive only for the largest names with tight fees. **Implication for us**: the structural reason our `Order Book` dimension scores zero is that we poll at 30s, not stream at 1s. The signal is there — at the right time resolution and the right venue access. Reinforces our v5 decision to zero-weight the dimension rather than chase a costly streaming infrastructure.

### arXiv 2506.05764 — *Exploring Microstructural Dynamics in Cryptocurrency Limit Order Books* (2025)

**URL.** <https://arxiv.org/html/2506.05764v2>

**What we learned.** Argues that **better input features matter more than deeper neural architectures** for LOB prediction. A well-designed CatBoost on the right inputs beats a deep LSTM on poor inputs. Useful methodological prior for us: when we eventually revisit microstructure signals, the lift comes from upstream data quality, not from model complexity.

### arXiv 2512.22476 — *AutoQuant* (Kaihong Deng, Dec 2025)

**URL.** <https://arxiv.org/abs/2512.22476>

**What we learned.** Quantifies how badly **execution delay, funding, fees, and slippage inflate naive backtest results**. Fee-only backtests overstate annualized returns vs. fully-costed configurations by material amounts. The paper's "two-stage double screening" methodology reduces drawdowns under strict cost semantics even when returns aren't higher. **Implication for us**: our `OutcomeEvaluator.feesInRUnits` cost subtraction is the *minimum* honest measurement; we should also account for funding accrual per outcome and the latency between signal fire and order-acknowledged-by-Bybit. Tracked as ongoing work.

## Trailing stops

### arXiv 1701.03960 — *Optimal Trading with a Trailing Stop* (Leung & Zhang, 2017/2019)

**URL.** <https://arxiv.org/abs/1701.03960>

**What we learned.** Formal treatment of when to *buy* an asset given that you'll exit via a fixed trailing stop. Models the problem as a double stopping time with a random path-dependent maturity. Proves the optimality of using a sell-limit-order *in conjunction with* the trailing stop. **Implication for us**: the combination of (target order = limit, stop = stop-market) we use on Bybit is theoretically sound, not just operationally convenient — Leung & Zhang's result is that the limit-target plus trailing-stop combination dominates either alone.

### Glynn & Iglehart — *Trading Securities Using Trailing Stops* (1995)

**Citation.** Glynn, P. W. & Iglehart, D. L. (1995). *Trading Securities Using Trailing Stops.* Management Science 41(6):1096-1106.

**URL.** <https://pubsonline.informs.org/doi/10.1287/mnsc.41.6.1096>

**What we learned.** The foundational paper on trailing stops. Uses discrete-time random walk and Brownian-motion-with-drift models to derive optimal distance from current price to stop. Result: the optimal trailing distance grows with volatility and shrinks with drift. **Implication for us**: directly justifies the ATR-relative formulation of trail offset (the offset *should* scale with realized volatility) and the two-rung widening at 2.5R MFE (when the trade has drifted favorably, the optimal trail distance widens to capture the asymmetric continuation probability).

## Crowded trades

### Stein — *Sophisticated Investors and Market Efficiency* (2009)

**Citation.** Stein, J. C. (2009). *Sophisticated Investors and Market Efficiency.* Journal of Finance 64(4):1517-1548.

**URL.** <https://onlinelibrary.wiley.com/doi/10.1111/j.1540-6261.2009.01471.x>

**What we learned.** Crowded trades persist when investors are uncertain about how many other investors are running the same strategy. Especially severe for non-fundamentally-anchored strategies, where the "right" position size depends on what everyone else is doing. **Implication for us**: most crypto TA strategies fall into the "non-fundamentally anchored" category. The mitigation is detector diversity (`LiquiditySweepDetector` and `TrendContinuationDetector` exit on different conditions) and explicit per-symbol Sharpe tracking — when a strategy stops working on a symbol, our `SymbolPerformanceGate` pulls it.

## Cryptocurrency-specific funding & basis

### He, Manela, Ross, von Wachter — *Fundamentals of Perpetual Futures* (arXiv 2212.06888)

**URL.** <https://arxiv.org/abs/2212.06888>

**What we learned.** Rigorous no-arbitrage treatment of perpetual futures. Unlike fixed-maturity futures, perpetuals are *not* guaranteed to converge to spot. The funding-rate mechanism creates only a *bound*, not equality, on the perp-spot gap. **Implication for us**: when our `Derivatives` dimension reads "funding rate = X", that's information about *recent past* positioning, not about expected mean-reversion to spot. Funding is laggy by design.

### arXiv 2506.08573 — *Designing Funding Rates for Perpetual Futures in Cryptocurrency Markets* (Jun 2025)

**URL.** <https://arxiv.org/abs/2506.08573>

**What we learned.** Proposes alternative funding-rate mechanisms (tradable and non-tradable variants) that close the perp-spot gap analytically. The current 8-hour TWAP is path-dependent and leaves systematic gaps even at equilibrium. **Implication for us**: a future opportunity to score "premium/discount of perp vs spot vs funding-rate-anchor" as a regime indicator — when these three diverge, the market is in transition.

### arXiv 2310.14973 — *Reconciling Open Interest with Traded Volume in Perpetual Swaps*

**URL.** <https://arxiv.org/pdf/2310.14973>

**What we learned.** Methodology for distinguishing "new positions opening" from "existing positions rotating" using OI deltas alongside volume. Naively reading volume as flow can over-count by 2-3×. **Implication for us**: our `Derivatives` dimension's OI-component should differentiate "OI rising on rising price = trend confirmation" from "OI rising on flat price = positioning building" — we don't currently make this distinction explicit.

## Further crypto-specific reading

- **arXiv 2412.04263 — Correlation without Factors in Retail Cryptocurrency Markets** — argues that observed cross-asset correlation in retail-traded crypto pairs doesn't require fundamental factor structure; trader behavior alone explains it. <https://arxiv.org/pdf/2412.04263>
- **arXiv 1811.07860 — Cryptoasset Factor Models** — early systematic factor exploration in crypto; useful baseline for what 2018-era researchers had vs. the 2025 LASSO papers. <https://arxiv.org/pdf/1811.07860>
- **arXiv 2501.07135 — Follow the Leader: Enhancing Systematic Trend-Following Using Network Momentum** — uses graph-based connectivity between assets to refine trend signals; relevant if we add multi-symbol cross-effects. <https://arxiv.org/html/2501.07135v1>
- **Macrosynergy — Crowded Trades and Consequences** — practitioner-flavored writeup with macro framing. <https://macrosynergy.com/research/crowded-trades-and-consequences/>
