# Practitioner Blogs and Research Sources

> Curated set of crypto and quant-trading publications that produce material worth reading repeatedly. Each entry: one-line value prop, who it's useful to, the rough cadence of new content.

## On-chain / crypto market structure

### Coin Metrics — *State of the Network*

**URL.** <https://coinmetrics.io/insights/state-of-the-network/> (Substack: <https://coinmetrics.substack.com/>)

**Value prop.** Weekly on-chain newsletter from a serious data company. State-of-the-network reports synthesize Coin Metrics' Network Data Pro feed (cohort metrics, LTH/STH supply, realized cap variants) into accessible narrative. Their methodology rigor — explicit definitions of every metric, source-data caveats — sets the standard for what on-chain research should look like.

**Best for.** Anyone consuming on-chain signals. Anchor read before forming any on-chain-driven thesis.

### Glassnode Insights

**URL.** <https://insights.glassnode.com/>

**Value prop.** Long-form deep-dives on Bitcoin/Ethereum cycle structure, holder cohort behavior, and on-chain regime classification. The Week On-Chain newsletter is the workhorse; the longer "Analyses" pieces are where novel methodology debuts. Glassnode also publishes their metric definitions publicly, which is essential for reproducibility.

**Best for.** Multi-week to multi-month positional traders. The signal Glassnode picks up is slow-moving relative to our 4h-candle horizon, but extremely useful as a regime overlay.

### CryptoQuant — Quicktake + research

**URL.** <https://cryptoquant.com/> (research feed under "Quicktake")

**Value prop.** Exchange-flow-focused. Their daily inflow/outflow charts and exchange-reserve metrics are the canonical reference for "are coins moving onto exchanges to be sold or off them to be HODL'd." Quicktake daily posts are noisier than Glassnode but timelier.

**Best for.** Short-to-medium-horizon flow analysis. We'd consume their netflow-by-exchange API if we had budget; the free signal still drives our thinking on `Whale` dimension construction.

## Macro and multi-asset

### Macrosynergy — research notes

**URL.** <https://macrosynergy.com/research/>

**Value prop.** Quantamental research applied to multi-asset (including crypto). Topics include "crowded trades and consequences," the Sharpe stability ratio, factor-construction methodology. Tied to the JPMaQS dataset (J.P. Morgan Macrosynergy Quantamental System).

**Best for.** When you want academic-quality methodology applied to actually-tradeable signals. Higher rigor than retail blogs, more accessible than peer-reviewed papers.

### Quantpedia — strategy encyclopedia

**URL.** <https://quantpedia.com/>

**Value prop.** Catalog of academic-paper-derived trading strategies with replicated results, parameters, and sample code. The "Pro" subscription unlocks full replications; the free blog covers new additions and methodology critiques. Less crypto-focused than Macrosynergy but increasingly adding crypto strategy reviews.

**Best for.** When evaluating whether a strategy idea has been studied before, and what the academic literature says about its expected Sharpe and drawdown.

### Quantocracy — quant link aggregator

**URL.** <https://quantocracy.com/>

**Value prop.** RSS aggregation of quant blogs across the spectrum — from retail-TA writers through institutional shops. The signal-to-noise is variable but you'll find papers and posts here weeks before they trend elsewhere.

**Best for.** Discovery. Skim weekly to surface authors and topics worth deeper investigation.

### QuantSeeker — weekly research recap

**URL.** <https://www.quantseeker.com/>

**Value prop.** Weekly digest of quant research papers with brief annotations. Same discovery role as Quantocracy, more editorial filter.

**Best for.** Time-constrained reading. The annotations let you decide what to actually read before clicking through.

## Crypto VC and thesis-driven research

### ARK Invest — Big Ideas + research

**URL.** <https://www.ark-invest.com/articles>

**Value prop.** Cathie Wood's firm publishes annual *Big Ideas* reports projecting adoption curves and market sizes. Their *Big Ideas 2026* report includes a $28T total crypto market projection by 2030 with BTC at $16T. Methodology is forward-looking and assumption-heavy — read for framing, not for entry signals. ([Cryptopolitan summary](https://www.cryptopolitan.com/cathie-wood-ark-invest-crypto-market-cap/))

**Best for.** Multi-year horizon framing. The numbers themselves are unfalsifiable point predictions; the *structure* of their argument (which use cases drive value, which networks capture it) is useful.

### Multicoin Capital — investment theses

**URL.** <https://multicoin.capital/>

**Value prop.** Thesis-driven crypto VC. Their public investment memos (Kyle Samani's "Crypto Mega Theses," Tushar Jain's network-effect pieces) are dense, opinionated, and frequently right about the multi-year direction of crypto verticals. They also publish detailed post-mortems on positions that didn't work, which is rare in the industry.

**Best for.** Understanding the *strategic* context of crypto market moves — why Solana matters, why infrastructure tokens trade differently than app tokens.

### Variant Fund — *Autonomy* thesis

**URL.** <https://variant.fund/writing/>

**Value prop.** Jesse Walden's crypto VC. Original thesis around "users becoming owners through tokenization" expanded in 2026 to "autonomy" — including agentic / AI-driven applications running on crypto rails. Their writing is more accessible than Multicoin's but covers similar strategic ground.

**Best for.** Identifying which token categories are likely to attract attention and capital over the next 12-24 months.

## Options and volatility

### Coinbase Prime Insights (formerly Skew)

**URL.** <https://www.coinbase.com/institutional/research-insights> (Skew rebranded after Coinbase's April 2021 acquisition)

**Value prop.** Skew was the gold standard for crypto options/derivatives analytics from 2018-2021 — IV surfaces, term structure, basis charts. Now integrated into Coinbase Prime's research feed. Recent material focuses on institutional positioning narratives more than pure options analytics. ([BitcoinMagazine on the acquisition](https://bitcoinmagazine.com/business/coinbase-acquires-skew))

**Best for.** Institutional crypto context. Less retail-actionable than it was as standalone Skew.

### Deribit Insights

**URL.** <https://insights.deribit.com/>

**Value prop.** Deribit's research arm publishes options-flow narratives, market-maker commentary, and special-situation analyses around big expiries. Most useful around quarterly expiries when their data shows hedging flows clearly.

**Best for.** Options-specific context. Effectively the "options sentiment" feed for ETH especially, where Deribit holds >90% of open interest.

## Reading discipline

A short personal rule used by most of the practitioners listed above: **read research with the explicit question "would this change a decision I'd make tomorrow?"** If the answer is no, the read is entertainment. If the answer is yes, capture the change in a follow-up note (or a CLAUDE.md update, or a new entry in this KB).

The list above is intentionally short. Adding more sources without filtering is how teams drown in feeds without absorbing any of them. Better to read three sources weekly with discipline than ten sources occasionally.
