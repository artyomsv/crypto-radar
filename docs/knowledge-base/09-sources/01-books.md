# Books — Annotated Bibliography

> The foundational systematic-trading library, filtered for what's directly load-bearing on projectr-x's design decisions. Listed in rough order of how often each is cited inside the codebase or in the rest of this KB.

## Risk-first frameworks and R-multiples

### Robert Carver — *Systematic Trading* (2015)

The book that puts risk sizing first and signal design second. Carver's framework defines positions in units of *forecast strength* applied against a target volatility, not in dollars. R-multiples (risk-multiple of stop distance) are presented as the only honest unit for evaluating strategies across asset classes and vol regimes.

**Why it matters for projectr-x.** Our entire outcome-tracking schema (`signal_outcomes.realized_r_multiple`, `max_favorable_pct`, `max_adverse_pct`) is structured around R rather than percent because of Carver's argument that R is the only metric comparable across symbols and time. The default trail config (`activationR=1.0`, `stepR=0.5`, `offsetR=0.5`) is conceptually a Carver-style "give the trade room equal to one unit of risk before tightening." Carver also explicitly endorses ATR-relative position sizing — which is what we do via `STOP_ATR_MULTIPLE` in `TrendContinuationDetector` and the ATR-relative pierce/stop math in `LiquiditySweepDetector`.

URL: <https://www.systematicmoney.org/systematic-trading>

### Robert Carver — *Leveraged Trading* (2019)

Retail-focused extension of *Systematic Trading*. Concrete recipes for futures, FX, and (importantly for us) **leveraged crypto perpetuals**: target leverage, position sizing, the case for diversification across uncorrelated systems.

**Why it matters for projectr-x.** The execution side of projectr-x runs leveraged Bybit perps; Carver's guardrails for leveraged retail-style systems map almost directly to our `GuardrailPolicy` design (`maxDailyLossPercent`, kill-switch). The book also argues forcefully against over-fitting individual strategies — the rationale for running multiple detectors (`LiquiditySweepDetector` + `TrendContinuationDetector`) rather than chasing the single highest backtested edge.

URL: <https://www.systematicmoney.org/leveraged-trading>

### Van K. Tharp — *Trade Your Way to Financial Freedom*

The origin of "R-multiple" as a teaching concept (Tharp coined it). Emphasizes expectancy over win-rate, position sizing as the dominant variable, and trader psychology — though we use the book mostly for its risk arithmetic, not its psychology framing.

**Why it matters for projectr-x.** Every time we discuss "expectancy over win-rate" in CLAUDE.md or in the empirical-findings docs, we're channeling Tharp. The R-multiple ledger schema is downstream of his definitions.

URL: <https://www.vantharp.com/products/books/>

## Trend-following and momentum

### Andreas Clenow — *Following the Trend* (2012)

The most readable rigorous account of how CTA-style trend-following works mechanically. Donchian channels, Plunger Index, 3-ATR trailing stops, position sizing by volatility. Backtests are end-to-end and replicable.

**Why it matters for projectr-x.** The 3-ATR trailing stop norm is the canonical reference for our trail design. We use a tighter activation (1R) and step (0.5R) than Clenow's defaults because crypto's noise floor is higher than commodities — but the conceptual structure (volatility-scaled trailing stop that ratchets but never tightens) is his. The "let your winners run" instinct codified in the second-rung wider offset (`widerOffsetR=1.0` at MFE ≥ 2.5R) is also Clenow-flavored.

URL: <https://www.followingthetrend.com/>

### Andreas Clenow — *Stocks on the Move* (2015)

Momentum-strategy companion to *Following the Trend*. The "stocks on the move" ranking screen — rolling-period exponential-regression slope adjusted for R² — is a clean alternative to naive momentum scores.

**Why it matters for projectr-x.** Less directly used than *Following the Trend*, but Clenow's argument that **rank-based ensemble strategies beat single-asset strategies on the same horizon** is one reason we run dimension-scoring across 13 symbols rather than concentrating on BTC/ETH.

URL: <https://www.followingthetrend.com/stocks-on-the-move/>

### Cliff Asness, Tobias Moskowitz, Lasse Pedersen — *Value and Momentum Everywhere* (2013)

Not a book — Journal of Finance paper — but the academic foundation for "momentum works across asset classes." Documented in `02-papers.md` as well; included here because the AQR-related book-length treatments distill its conclusions.

**Why it matters for projectr-x.** Justification for taking momentum/trend signals seriously on crypto even though crypto wasn't in the original sample. The factor structure has held in crypto-specific replications (see Liu et al. 2019).

DOI: <https://doi.org/10.1111/jofi.12021>

## Algorithmic / Statistical

### Ernest P. Chan — *Algorithmic Trading* (2013) and *Machine Trading* (2017)

Chan's books are the practical bridge between an academic-style strategy idea and a working backtest. *Algorithmic Trading* covers mean reversion, momentum, and seasonal strategies with MATLAB/Python recipes. *Machine Trading* extends to ML-based and intraday systems.

**Why it matters for projectr-x.** Chan's chapter on pair / cointegration arbitrage informs the basis-trade and funding-arb opportunities discussed in `01-market-structure/05-cross-exchange-arbitrage.md`. His extensive warnings about look-ahead bias and survivorship bias inform our hygiene around backfilled outcome data. The "Sharpe vs trades-per-day" curves in both books are useful intuition for evaluating whether a detector is worth keeping.

URLs: <https://www.epchan.com/books/> and <https://www.amazon.com/Machine-Trading-Deploying-Computer-Algorithms/dp/1119219604>

### Marcos López de Prado — *Advances in Financial Machine Learning* (2018)

The current canonical reference for ML applied to trading. Triple-barrier labeling, sample uniqueness, purged k-fold CV, deflated Sharpe ratio. Rigorous, opinionated, and explicitly aware of how easy it is to overfit financial data.

**Why it matters for projectr-x.** Triple-barrier labeling — labeling each event by which barrier (target, stop, time) it hits — is exactly the schema we use for `signal_outcomes.final_exit_reason` (TARGET / TRAIL / INITIAL_STOP / STAGNATION / EXPIRED). The deflated Sharpe argument (multiple-testing-corrected Sharpe) is the reason we have a `deployment_markers` table — to slice metrics into honest pre/post-change cohorts rather than data-mining the full history. The book's emphasis on "fees, slippage, funding *or your backtest is a lie*" is restated in our `AutoQuant` citation and is encoded in our `fees_bps_round_trip` column.

URL: <https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086>

## Options and volatility

### Sheldon Natenberg — *Option Volatility & Pricing* (1994/2014)

The market-makers' standard reference for options. Greeks, volatility skew, position management. Practitioner-focused, light on stochastic-calculus apparatus.

**Why it matters for projectr-x.** Options data is not yet integrated, but if/when we add a `Volatility` dimension (25-delta skew, term-structure slope, RV-IV gap) Natenberg's framing of *why* skew exists is the prerequisite reading. The book also clarifies what "implied volatility" actually means as a quoting convention, which matters when consuming Deribit data.

URL: <https://www.amazon.com/Option-Volatility-Pricing-Strategies-Techniques/dp/155738486X>

### Euan Sinclair — *Volatility Trading* (2013, 2nd ed.)

More quantitative than Natenberg. Covers vol forecasting, vol-of-vol, and the realized-implied vol gap as a tradeable spread.

**Why it matters for projectr-x.** Sinclair's argument that **realized vol persistently undershoots implied vol** is the foundation for any "sell options for premium" strategy. We don't sell options currently, but if we ever offer that as a strategy or use it as a sentiment input, this is the rigorous treatment.

URL: <https://www.wiley.com/en-us/Volatility+Trading%2C+2nd+Edition-p-9781118347133>

### John C. Hull — *Options, Futures, and Other Derivatives* (every edition)

The graduate textbook. Black-Scholes, binomial trees, Greek derivations, exotic options. Reference, not strategy book.

**Why it matters for projectr-x.** When we need to look up the exact derivation of an exotic Greek or settle a math dispute, Hull is the arbiter. Otherwise rarely cited in day-to-day work.

URL: <https://www.pearson.com/en-us/subject-catalog/p/options-futures-and-other-derivatives/P200000005983>

### Colin Bennett — *Trading Volatility* (2014)

Bennett's free PDF (originally from Santander Equity Derivatives) is a goldmine of practitioner-grade options strategies and quirks — gamma scalping, variance swaps, dispersion, structured products. Less rigorous than Sinclair, broader than Natenberg.

**Why it matters for projectr-x.** Reference for the *menagerie* of options-based strategies that exist beyond simple long/short calls. If we ever consider exposing options-based hedges to users, the option-strategy chapters here cover the realistic universe.

URL (free PDF available widely): <https://www.amazon.com/Trading-Volatility-Variance-Swaps-Dispersion/dp/1461108756>

## Specialty / Strategy

### Ganapathy Vidyamurthy — *Pairs Trading* (2004)

Statistical-arbitrage focused. Cointegration, Kalman filters, stochastic-process-based pair selection.

**Why it matters for projectr-x.** Not currently used — projectr-x is single-symbol, directional. If we ever add a `Pairs` strategy (BTC-ETH spread, BTC-major-altcoin spreads), this is the entry point. The argument that pair spreads are mean-reverting more reliably than absolute prices is the structural rationale.

URL: <https://www.wiley.com/en-us/Pairs+Trading%3A+Quantitative+Methods+and+Analysis-p-9780471460671>

### Edward O. Thorp — *A Man for All Markets* (2017)

Thorp's memoir of beating roulette, blackjack, and then markets. The "Kelly criterion" sections are the practical retail-friendly treatment of optimal position sizing — bet a fraction of bankroll equal to expected edge over odds.

**Why it matters for projectr-x.** Kelly fractions are the theoretical optimum for compounding strategies under known edge. We don't apply pure Kelly (it's catastrophically over-aggressive on noisy edge estimates), but the "half-Kelly" rule of thumb that retail systems should bet *half* of Kelly to survive estimation error is built into our per-trade-risk caps in the execution policy. The book is also a nice corrective to the survivorship bias in trading literature — Thorp's edge was real, measurable, and explainable.

URL: <https://www.amazon.com/Man-All-Markets-Street-Dealer/dp/1400067960>
