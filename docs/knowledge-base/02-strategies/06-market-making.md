# Market Making

> Quote both sides of the order book, earn the spread, manage inventory so the directional risk doesn't kill the franchise. We don't do this — but understanding the mechanics is essential for modelling slippage and execution cost.

## Definition

A market maker continuously posts bid and ask quotes on a venue and earns the spread (mid-to-quote distance, times two) on each round-trip. The trade is structurally different from a directional strategy:

- **No view on direction.** The market maker doesn't predict where price is going. They predict the *rate of order arrival* and the *toxicity* of those arrivals (the probability that an incoming order is informed).
- **Inventory risk dominates.** Every fill leaves the maker with inventory in the direction opposite to the incoming order flow. If buys exceed sells, the maker accumulates a long position whose mark-to-market PnL depends on subsequent price moves. The strategy's risk is the *time path of inventory*, not the spread per se.
- **Adverse selection.** When an informed trader knows price is about to rise, they consume the maker's ask. The maker's "fill" was negatively selected — they're now short ahead of a real up-move, and the spread earned doesn't compensate. The empirical edge of market making lives in distinguishing toxic from benign flow.

The classical pricing model is **Avellaneda-Stoikov (2008)** — a continuous-time stochastic-control formulation where the maker solves for optimal bid/ask offsets given:

- Current inventory `q`
- Mid-price `s`
- Reservation price `r = s − q × γ × σ² × (T − t)` — the inventory-adjusted "fair value" the maker believes
- Spread `δ = γ × σ² × (T − t) + (2/γ) × ln(1 + γ/k)` — where γ is risk aversion, σ is mid-price volatility, k is the order-arrival rate decay parameter

The intuition: when you're long, you skew quotes lower (cheaper ask, lower bid) to bias outgoing flow toward sells; when you're short, you skew higher. Spread widens with inventory, with volatility, with risk aversion, and with proximity to a terminal time horizon.

Modern HFT market making elaborates the model with:

- **Order-book imbalance signals** — top-of-book bid size / total top size predicts next-tick direction (Cont, Kukanov, Stoikov 2014).
- **Microprice estimates** — a refinement of mid-price weighted by book imbalance (Stoikov 2018).
- **Latency-arbitrage defence** — quotes that respond to a real "trade" tick within microseconds, pulling stale quotes before being picked off by faster participants.
- **Cross-venue inventory netting** — quoting on multiple exchanges and dynamically rebalancing where the inventory aggregates.

## When it works

- **High-volume, low-toxicity venues.** Equity ETFs, futures benchmarks, FX majors — high turnover, narrow informed-flow fraction, predictable arrival rates.
- **Inventory-bounded environments.** When inventory limits are enforced (positional risk capped at, say, $1M per symbol) and the maker can reliably hedge in correlated venues, the inventory-risk premium gets paid out as net spread.
- **Crypto majors on a multi-venue basis.** BTC/USDT spot on Binance, Coinbase, Kraken, Bybit — top firms run a unified inventory book and quote into all simultaneously; spread + cross-venue arbitrage compounds.
- **Predictable session structures.** Crypto perpetuals have no "session" but funding-rate events at 00:00/08:00/16:00 UTC introduce predictable order-flow surges; designing quotes around those events is profitable.

## When it fails

- **Adverse selection during news / flash events.** An unannounced macro print, a regulatory headline, an exchange outage on a competitor — informed flow consumes the maker's quotes in one direction before quotes can be pulled. A few seconds of one-way fills at stale quotes can wipe a quarter's PnL.
- **Inventory runaway in trending regimes.** A strong directional move means the maker keeps getting hit on one side (sells in a down-trend, buys in an up-trend) and accumulates inventory in the direction of the loss. Without aggressive skew or hedging, the inventory PnL hit dwarfs the spread earned.
- **HFT arms race.** Modern market making rewards co-location, FPGA execution, microsecond-level book engineering. A naïve participant gets picked off by faster ones — the inventory you accumulate is *systematically* the side of the trade about to lose.
- **Crypto exchange API throttling.** Bybit (and most crypto venues) enforce request-rate limits that prevent the kind of microsecond quote-update cycle HFT market makers run in equities. Building a competitive market-making system on Bybit V5's public REST + WS API is borderline infeasible without colocation/private-endpoint access.
- **Maker rebate structures.** A market-making strategy that relies on a maker rebate (negative fees for resting orders) is exposed to exchange fee-schedule changes. Several exchanges have cut maker rebates over time; what was a positive-expectancy strategy at -2bps/+5bps becomes break-even at 0/+5bps.
- **Black swan exchange failures.** FTX, Mt. Gox, Quadriga — a market maker who held inventory or capital on the failed venue lost everything. The risk is structural, not statistical.

## What we do today (in projectr-x)

**We do not run a market-making strategy.** Our execution path (`services/trade-execution-service/`) is exclusively a **taker** — we cross the spread with market or aggressive-limit orders to enter, and we maintain native Bybit stops/targets that fire as triggers (which execute as market orders, not maker quotes).

The maker/taker decision lives implicitly in `OrderPlacer` and the Bybit V5 client (`services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java`). Our default order type is taker because:

1. Signal-driven entries on a 1-15 minute decision horizon don't have the time-budget to wait for a maker fill.
2. Bybit's taker fee is 0.055% (0.11% round-trip), which we explicitly model in `OutcomeEvaluator.feesInRUnits` and against which all R-multiple math is net.
3. Slippage on small ($100-1000) order sizes against Bybit's perp order books is negligible compared to the 1R/cycle decisions our detectors make.

Why market making knowledge is still relevant for us:

- **Slippage modelling for sizing.** As account sizes grow toward 5+ figures, the assumption "I get filled at last-price" breaks. Avellaneda-Stoikov microprice estimates are the cleanest way to estimate the expected fill price for a market order of size Q against the visible book.
- **Order-book dimension scoring.** `SignalEngine` includes an `"Order Book"` dimension (note the space — see `CLAUDE.md` conventions). The signals it consumes — bid/ask imbalance, depth ratios — are exactly the features market-making algos use to forecast short-horizon price drift. Improving that dimension means borrowing from market-making research literature.
- **Liquidation cascade prediction.** Forced liquidations consume the order book in one direction; a market maker's perspective on "how deep is the book?" maps directly to "how big a liquidation can clear without a 5σ move?" — relevant for the `LiquiditySweepDetector` confluence checks.

## Implementation sketch (if we ever ship MM)

We almost certainly shouldn't ship market making on Bybit. The infrastructure overhead and adverse-selection exposure don't fit our prototype-stage architecture or competitive position. But if we did, the minimum viable shape:

- **New service**: `market-making-service` running its own Bybit V5 WS connection (separate from `trade-execution-service`) with `<200ms` round-trip quote update latency target.
- **Per-symbol quote engine**: simple Avellaneda-Stoikov implementation with γ as a config knob, σ from realised vol on the symbol's 1-minute candles (we already compute this in `RealizedVolService` for the options-service — could be shared).
- **Inventory cap**: hard limit on |inventory_usd| per symbol; once breached, only the side that reduces inventory is quoted.
- **Adverse-selection guard**: pull quotes when a trade prints `>= N` standard deviations of recent prints; resume after `T` seconds.
- **Fee/PnL accounting**: per-quote-cycle PnL ledger, distinguishing spread captured, inventory MTM, and any rebates.
- **Effort**: ≥4 weeks for a non-competitive version, ≥6 months for one that doesn't bleed to better-resourced HFTs.

The honest verdict: **don't ship it on Bybit retail API.** If we ever want this exposure, it goes through a maker-friendly venue (dYdX v4, Vertex) or stays out of scope.

## Sources

1. **Avellaneda, M., & Stoikov, S. (2008). "High-frequency trading in a limit order book." *Quantitative Finance*.** https://www.tandfonline.com/doi/abs/10.1080/14697680701381228 — The canonical optimal market making model under inventory risk; basis for almost all modern academic and practitioner extensions.
2. **Stoikov, S. (2018). "The micro-price: A high-frequency estimator of future prices." *Quantitative Finance*.** https://www.tandfonline.com/doi/abs/10.1080/14697688.2018.1489139 — Inventory-aware fair-value estimator that improves on mid-price; used in most modern MM stacks.
3. **Cont, R., Kukanov, A., & Stoikov, S. (2014). "The Price Impact of Order Book Events." *Journal of Financial Econometrics*.** https://academic.oup.com/jfec/article/12/1/47/784440 — Foundation for order-book-imbalance signals in HFT and MM.
4. **Bouchaud, J.-P., Bonart, J., Donier, J., & Gould, M. (2018). *Trades, Quotes and Prices: Financial Markets Under the Microscope*. Cambridge University Press.** https://www.cambridge.org/core/books/trades-quotes-and-prices/ — Comprehensive textbook on order-book microstructure; the modern academic reference.
5. **Harris, L. (2003). *Trading and Exchanges: Market Microstructure for Practitioners*. Oxford University Press.** — Foundational practitioner reference for understanding why bid-ask spreads exist, how informed flow drives them, and how exchanges price-discriminate among participants.
6. **Glosten, L. R., & Milgrom, P. R. (1985). "Bid, Ask and Transaction Prices in a Specialist Market with Heterogeneously Informed Traders." *Journal of Financial Economics*.** https://www.sciencedirect.com/science/article/abs/pii/0304405X85900443 — Adverse selection model — the canonical theoretical basis for why market makers lose to informed flow.
