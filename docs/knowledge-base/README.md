# projectr-x Trading & Markets Knowledge Base

This directory is the **product's institutional memory** for everything market-, strategy-, and methodology-related. When a future Claude session, contributor, or you-in-six-months asks "what's the right way to size positions?" or "is this strategy worth implementing?" — the answer lives here, with sources, not in someone's head.

The goal is not to be exhaustive about all of finance. The goal is to be **opinionated, cited, and project-specific** about the slice of finance that matters for `projectr-x`: short-horizon crypto signal generation, multi-detector trade setups, execution on Bybit V5 perpetuals, and the supporting analytics (whale flow, derivatives, sentiment, options vol).

## How to use this knowledge base

| You're trying to… | Start here |
|---|---|
| Understand why a strategy is in the codebase | `02-strategies/` then `10-projectr-x-mapping/02-implemented-detectors.md` |
| Decide whether to add a new detector | `02-strategies/` for theory → `10-projectr-x-mapping/03-roadmap-ideas.md` for what's been considered |
| Argue about a risk-management choice | `05-risk-and-execution/` — every doc has a "what we do today" note |
| Look up a specific paper or book | `09-sources/` |
| See what the engine empirically did | `10-projectr-x-mapping/04-empirical-findings.md` |
| Wire up a new dimension or score | `04-quant-methods/04-feature-engineering.md` + `08-prediction-frameworks/` |

## Directory map

```
docs/knowledge-base/
├── 01-market-structure/      How crypto markets actually work — spot, perp, options, order book
├── 02-strategies/            Named strategies — what they are, when they work, when they fail
├── 03-technical-analysis/    TA indicators — math, what they measure, common misuses
├── 04-quant-methods/         Statistical & ML methods for trading — labeling, validation, evaluation
├── 05-risk-and-execution/    Position sizing, stops, fees, slippage, leverage
├── 06-derivatives/           Perp/options mechanics — funding, Greeks, vol surface, strangles
├── 07-crypto-specific/       BTC cycles, Bybit specifics, on-chain whale flow, altcoin rotation
├── 08-prediction-frameworks/ Regime classification, Bayesian, Monte Carlo, ensembles
├── 09-sources/               Annotated bibliography — books, papers, blogs, dashboards
└── 10-projectr-x-mapping/    Theory ↔ code crosswalk + current state + roadmap
```

## Contribution rules

Every document follows the same shape so they're skim-able:

```markdown
# {Topic}

> One-sentence summary. What it is.

## Definition
2-4 paragraphs — precise, not Wikipedia-vague.

## When it works
The market conditions, asset class, horizon under which this performs.

## When it fails
The known failure modes. This section is mandatory — silence means
the doc author didn't think hard enough.

## What we do today (in projectr-x)
Maps theory to actual code. Files, classes, constants, deployment markers.
Empty if not yet implemented — say so explicitly.

## Implementation sketch (if not implemented yet)
Where it would live, what it would touch, rough effort.

## Sources
Numbered, with URLs and a one-line note on what we learned from each.
Prioritize: peer-reviewed > book chapters > named practitioners > vendor blogs.
```

**Sourcing standards.** Every empirical claim gets a citation. "Crypto trends mean-revert at 30s" with no source is folklore; with a paper URL it's a position. Folklore is fine in conversation, not here. If a claim is your own observation from our data, cite the empirical-findings doc with the deployment marker that produced it.

**Update on real changes only.** Don't churn these docs to match every code refactor. Update when the **strategy** changes, the **threshold** moves materially, or the **empirical evidence** flips. A constant getting tuned from 1.5 → 1.4 doesn't earn a doc update; replacing the stop logic with ATR-scaled bands does.

**Mark stubs honestly.** A doc that exists with `<!-- STUB -->` is a placeholder; an empty doc is misleading. Stubs are encouraged — they communicate "we know this matters, we haven't written it yet."

## Editorial bias

This KB is opinionated where the literature is opinionated:

- **R-multiples over win-rate** for evaluating strategies. Expectancy is what compounds. Win-rate is a vanity metric.
- **Triple-barrier labeling** (López de Prado) over single-target metrics.
- **ATR-relative thresholds** over absolute % thresholds. Crypto vol regimes span 2 orders of magnitude.
- **Sample size discipline.** Below n=30 per cell is anecdote, not edge. Bootstrap CIs over point estimates.
- **Fee-aware backtests.** A backtest that doesn't model fees, funding, and slippage lies — usually to your benefit.
- **Pre-registered hypotheses** for new detectors. We write the success criteria BEFORE shipping, not after measuring.

These biases come directly from the names cited heavily throughout: López de Prado, Carver, Clenow, Chan, and the AutoQuant/Hyper-Quant practitioner-facing literature.

## Full document index

### 01-market-structure
- [`01-spot-vs-derivatives.md`](01-market-structure/01-spot-vs-derivatives.md) — spot / futures / perp / options compared; volume profile
- [`02-perpetual-swaps.md`](01-market-structure/02-perpetual-swaps.md) — how perps work; funding rate as anchor
- [`03-options-mechanics.md`](01-market-structure/03-options-mechanics.md) — crypto vs equity options; Deribit dominance
- [`04-order-book-microstructure.md`](01-market-structure/04-order-book-microstructure.md) — LOB layout; why imbalance signal decays in seconds
- [`05-cross-exchange-arbitrage.md`](01-market-structure/05-cross-exchange-arbitrage.md) — triangular + cross-exchange; why retail can't compete on speed

### 02-strategies
- [`01-trend-following.md`](02-strategies/01-trend-following.md) — Donchian, MA crosses, Clenow Plunger; ↔ `TrendContinuationDetector`
- [`02-mean-reversion.md`](02-strategies/02-mean-reversion.md) — Bollinger snap, RSI reversal; regime-dependent
- [`03-liquidity-sweep-and-reversal.md`](02-strategies/03-liquidity-sweep-and-reversal.md) — stop-hunt geometry; ↔ `LiquiditySweepDetector`
- [`04-momentum.md`](02-strategies/04-momentum.md) — cross-sectional vs time-series; Asness/Moskowitz/Pedersen
- [`05-statistical-arbitrage.md`](02-strategies/05-statistical-arbitrage.md) — pairs/cointegration; why hard in crypto
- [`06-market-making.md`](02-strategies/06-market-making.md) — Avellaneda-Stoikov; why we don't (latency)
- [`07-volatility-trading.md`](02-strategies/07-volatility-trading.md) — long-vol via strangle when IV<<RV; ↔ `options-service`
- [`08-funding-rate-arbitrage.md`](02-strategies/08-funding-rate-arbitrage.md) — cash-and-carry on Bybit perp vs spot
- [`09-basis-trading.md`](02-strategies/09-basis-trading.md) — spot-perp basis as sentiment proxy
- [`10-news-sentiment.md`](02-strategies/10-news-sentiment.md) — short-horizon sentiment alpha; why our Sentiment dim is noise
- [`11-on-chain-signals.md`](02-strategies/11-on-chain-signals.md) — whale flow / exchange flows; ↔ `whale-service`

### 03-technical-analysis
- [`01-moving-averages.md`](03-technical-analysis/01-moving-averages.md) — SMA/EMA/WMA/HMA/TEMA; lag tradeoffs
- [`02-rsi-and-oscillators.md`](03-technical-analysis/02-rsi-and-oscillators.md) — RSI/Stoch/MACD; why 35-65 band for TC
- [`03-atr-and-volatility.md`](03-technical-analysis/03-atr-and-volatility.md) — ATR math, Chandelier, Keltner; why ATR-relative > absolute
- [`04-support-resistance.md`](03-technical-analysis/04-support-resistance.md) — swing detection, volume profile
- [`05-volume-analysis.md`](03-technical-analysis/05-volume-analysis.md) — volume confirmation, VWAP basics
- [`06-ichimoku.md`](03-technical-analysis/06-ichimoku.md) — cloud system; popular but designed for stocks
- [`07-bollinger-bands.md`](03-technical-analysis/07-bollinger-bands.md) — 2σ default; the squeeze; Keltner overlay
- [`08-vwap-and-anchored-vwap.md`](03-technical-analysis/08-vwap-and-anchored-vwap.md) — session VWAP vs anchored

### 04-quant-methods
- [`01-time-series-foundations.md`](04-quant-methods/01-time-series-foundations.md) — stationarity; log returns; ACF/PACF
- [`02-ml-for-trading.md`](04-quant-methods/02-ml-for-trading.md) — failure modes; when ML actually helps
- [`03-triple-barrier-labeling.md`](04-quant-methods/03-triple-barrier-labeling.md) — López de Prado; ↔ `OutcomeEvaluator` ATR-scaled stagnation
- [`04-feature-engineering.md`](04-quant-methods/04-feature-engineering.md) — rolling z-scores, look-ahead, multicollinearity
- [`05-overfitting-and-cv.md`](04-quant-methods/05-overfitting-and-cv.md) — purged k-fold + embargo; CLT floor
- [`06-deflated-sharpe.md`](04-quant-methods/06-deflated-sharpe.md) — Bailey & López de Prado; the multiple-testing correction
- [`07-walk-forward-analysis.md`](04-quant-methods/07-walk-forward-analysis.md) — proper IS/OOS protocol

### 05-risk-and-execution
- [`01-position-sizing-kelly.md`](05-risk-and-execution/01-position-sizing-kelly.md) — full/fractional Kelly; our 1% as quarter-Kelly
- [`02-r-multiples-and-expectancy.md`](05-risk-and-execution/02-r-multiples-and-expectancy.md) — Van Tharp framework; ↔ `realized_r_multiple`
- [`03-trailing-stops.md`](05-risk-and-execution/03-trailing-stops.md) — vol-calibrated trails; ↔ `TrailConfig.DEFAULT` + TC override
- [`04-fees-and-slippage.md`](05-risk-and-execution/04-fees-and-slippage.md) — Bybit V5 fees; Almgren square-root law
- [`05-leverage-and-liquidation.md`](05-risk-and-execution/05-leverage-and-liquidation.md) — Bybit MMR tiers; why 3x default
- [`06-portfolio-correlation.md`](05-risk-and-execution/06-portfolio-correlation.md) — when 13 "diversified" alts go down together

### 06-derivatives
- [`01-perp-funding-mechanics.md`](06-derivatives/01-perp-funding-mechanics.md) — premium index + interest + clamp; Bybit-specific
- [`02-options-greeks.md`](06-derivatives/02-options-greeks.md) — Δ, Γ, Vega, Θ, ρ for the strangle holder
- [`03-implied-vs-realized-vol.md`](06-derivatives/03-implied-vs-realized-vol.md) — IV term structure; ↔ `RealizedVolService`
- [`04-volatility-surface.md`](06-derivatives/04-volatility-surface.md) — smile/skew; crypto vs equity differences
- [`05-skew-and-term-structure.md`](06-derivatives/05-skew-and-term-structure.md) — 1d vs 30d IV; event-risk signal
- [`06-straddles-and-strangles.md`](06-derivatives/06-straddles-and-strangles.md) — long-vol setups; ↔ `OpportunityScorer.pickStrangle`

### 07-crypto-specific
- [`01-bitcoin-market-cycles.md`](07-crypto-specific/01-bitcoin-market-cycles.md) — halving cycles; stock-to-flow critique
- [`02-bybit-v5-api.md`](07-crypto-specific/02-bybit-v5-api.md) — V5 REST/WS structure; rate limits; ↔ `BybitV5RestClient`
- [`03-altcoin-rotations.md`](07-crypto-specific/03-altcoin-rotations.md) — BTC.D regimes; ETH/BTC ratio
- [`04-stablecoin-flows.md`](07-crypto-specific/04-stablecoin-flows.md) — USDT/USDC supply as on/off-ramp
- [`05-whale-onchain-tracking.md`](07-crypto-specific/05-whale-onchain-tracking.md) — whale definitions; exchange flows; ↔ `whale-service`
- [`06-defi-and-tradfi-rails.md`](07-crypto-specific/06-defi-and-tradfi-rails.md) — bridges, oracles, ETF flows, CME futures
- [`07-exchange-listings-impact.md`](07-crypto-specific/07-exchange-listings-impact.md) — listing pumps; delisting risk (XMR case)

### 08-prediction-frameworks
- [`01-regime-classification.md`](08-prediction-frameworks/01-regime-classification.md) — Markov regime switching, HMM; ↔ `MarketRegimeService`
- [`02-bayesian-updating.md`](08-prediction-frameworks/02-bayesian-updating.md) — posterior of "still in trend" given new evidence
- [`03-monte-carlo.md`](08-prediction-frameworks/03-monte-carlo.md) — bootstrap, parametric, GBM
- [`04-vector-autoregression.md`](08-prediction-frameworks/04-vector-autoregression.md) — VAR for multi-asset; why crypto regime breaks defeat it
- [`05-deep-learning-llms.md`](08-prediction-frameworks/05-deep-learning-llms.md) — LSTMs, transformers, GPT-4-class era; pragmatic limits
- [`06-ensemble-methods.md`](08-prediction-frameworks/06-ensemble-methods.md) — stacking, blending; ↔ `SymbolPerformanceGate` as proto-meta-labeling

### 09-sources
- [`01-books.md`](09-sources/01-books.md) — annotated bibliography (Carver, Clenow, López de Prado, Chan, Natenberg, Sinclair, Hull, …)
- [`02-papers.md`](09-sources/02-papers.md) — key papers with what-we-learned per entry (Deflated Sharpe, factor structure, microstructure, AutoQuant)
- [`03-data-sources.md`](09-sources/03-data-sources.md) — APIs we use / could use; tied to actual client classes
- [`04-practitioner-blogs.md`](09-sources/04-practitioner-blogs.md) — Coin Metrics, Glassnode, Macrosynergy, Quantpedia, …

### 10-projectr-x-mapping
- [`01-current-strategies.md`](10-projectr-x-mapping/01-current-strategies.md) — operator's view of every live strategy
- [`02-implemented-detectors.md`](10-projectr-x-mapping/02-implemented-detectors.md) — code-level inventory with file:line refs
- [`03-roadmap-ideas.md`](10-projectr-x-mapping/03-roadmap-ideas.md) — pre-registered hypotheses for next strategies
- [`04-empirical-findings.md`](10-projectr-x-mapping/04-empirical-findings.md) — what the engine actually did in 14d ending 2026-06-03
- [`05-vol-strategy-plan.md`](10-projectr-x-mapping/05-vol-strategy-plan.md) — Sinclair / López de Prado defined-risk short-vol roadmap, 4 tiers, pre-registered

## See also

- Repo root `CLAUDE.md` — project-level architecture and constraints
- `docs/signal-config-api.md` — operational REST docs for signal config
- `techdebt/` — known issues, separate from KB
