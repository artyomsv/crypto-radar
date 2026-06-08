# On-Chain Signals

> Crypto's unique data advantage: every settlement is public. Whale wallet movements, exchange inflows/outflows, dormant-coin reactivation — all directly observable. The signal is real but noisy, and "real" doesn't mean "easy".

## Definition

"On-chain signals" refers to trading-relevant features derived directly from blockchain settlement data — not exchange order books, not price candles, but the underlying transaction graph. Because every transfer of BTC, ETH, and most major tokens is permanently public, traders can observe activity that in traditional markets would be private (institutional flow, settlement timing, holder concentration changes).

The categories of signal that have credible empirical support:

1. **Exchange inflows/outflows.** Wallets identified as belonging to exchanges (via clustering heuristics) have inbound flow when holders move coins onto an exchange (often a precursor to selling) and outbound flow when holders withdraw (often a signal of long-term holding intent). Glassnode's exchange-flow series is the canonical reference; the signal has documented predictive power at the multi-day horizon.
2. **Whale wallet activity.** Wallets above a size threshold (10k+ BTC, 100k+ ETH, etc.) are tracked individually. A dormant whale wallet that wakes up after 5+ years is a notable event — Satoshi-era BTC movements have repeatedly preceded BTC price weakness.
3. **Realized cap, MVRV, NUPL.** Aggregate on-chain valuation metrics from David Puell, Willy Woo, and others. MVRV (market value / realized value) above 3.7 historically marked BTC cycle tops; below 1.0 marked bottoms.
4. **Coin Days Destroyed (CDD).** Weighted measure of how much "old coin" is moving. A spike in CDD means long-term holders are transacting — usually selling — and historically precedes meaningful price declines.
5. **Active addresses and transaction volume.** Network-activity heuristics; growth in active addresses leading price is one of the few results that survives across both Bitcoin and altcoin networks.
6. **Real-time large-trade tracking on exchanges.** Strictly speaking this is "off-chain" (it's exchange order/trade data), but it's often bundled with on-chain analysis because the methodology is similar: detect outsized actors and infer intent from their flow. Our `whale-service` falls in this category.

The high-quality academic and practitioner consensus is that on-chain features have **non-trivial predictive power at multi-day horizons**, weaker power at intraday horizons, and almost no signal at sub-hour timescales (where price impact dominates flow detection).

## When it works

- **Multi-day exchange-flow trends.** When the 7-day moving average of net BTC outflow from exchanges is sharply positive (coins leaving), the supply available for selling shrinks. Historically this has corresponded with mid-term price strength (correlation ≈0.3-0.5 at 7-30 day horizons in Glassnode's published data).
- **Dormant whale awakening events.** Specific named events: a 2010-vintage BTC wallet (Satoshi-era) becoming active is a tradeable signal because of the publicity it generates and the realistic likelihood of an OTC sale.
- **Stablecoin issuance flows.** A surge in USDT or USDC issuance (mint events on Tron/Ethereum) historically precedes BTC strength — new stablecoin supply often translates to capital inflow into crypto. Cycle-level signal, not minute-level.
- **Exchange aggregate-balance changes during stress.** During exchange-failure scares (Binance reserves audit, FTX collapse spillover), exchange-aggregate balance drops were leading indicators of where panic-withdrawal cascades would hit next.
- **Realized profit/loss capitulation.** Aggregate realized loss spiking — many holders selling at a loss — has historically marked local bottoms within hours-to-days. Inversely, aggregate realized profit at multi-cycle highs (e.g., 2017 Dec, 2021 Apr) has marked tops.
- **Cross-chain bridge flows.** A surge in capital crossing from Ethereum to Solana via Wormhole/LayerZero historically preceded SOL outperformance. Capital rotation is observable in real time.

## When it fails

- **OTC and dark-pool flow.** Large institutional sellers often use OTC desks (Cumberland, Genesis, B2C2) where the buyer's wallet doesn't appear on an exchange-cluster heuristic. The on-chain signal misses the entire trade — the seller's wallet moves coins to the OTC desk's wallet (not labelled as exchange), and the sale happens off-chain. Pre-FTX, this was a *huge* blind spot.
- **Wrapped / cross-chain assets.** A BTC moved to a Wrapped BTC contract on Ethereum doesn't reduce "Bitcoin exchange balance" in the standard heuristic — but it can flow into a DEX and be effectively sold. Our heuristic-based exchange-flow scoring undercounts these flows.
- **Wallet-clustering errors.** The methodology for identifying "exchange wallets" relies on transaction pattern heuristics that are imperfect. A common-input-ownership cluster can falsely group multiple exchanges; a CoinJoin or mixer obscures attribution entirely. Glassnode publishes confidence intervals; many vendors don't.
- **Frequency mismatch with trade horizons.** On-chain signals operate at multi-day frequencies. Our signal engine operates at 1-minute candle granularity. The two scales don't compose naturally — a 5-day-leading on-chain signal doesn't tell you whether to enter the trade today or in 4 days.
- **Whale flow ambiguity.** A whale moving 1k BTC from a personal wallet to a different personal wallet (internal accounting, security migration, multi-sig reconfiguration) looks identical on-chain to the same whale moving 1k BTC to a fresh exchange deposit address. False positives on "whale is dumping" headlines are common.
- **Survivorship bias on metric construction.** MVRV, NUPL, SOPR, CDD — these metrics were all developed *after* the 2013-2017 cycles where their historical fit looks great. Their performance in subsequent cycles (2018-2024) has been more mixed. Curve-fit on the dev sample, deteriorates out-of-sample.
- **Real-time exchange-trade "whale" signals are noisy.** A $100k BTC market buy on a single venue is a "whale trade" by retail standards but a routine inventory rebalance for an institutional desk. The trade itself doesn't tell you intent. Our whale-service captures these as data; turning them into reliable signal requires careful aggregation.

## What we do today (in projectr-x)

The `whale-service` is the on-chain/large-trade tracker (`services/whale-service/`). Specifically:

- **`ExchangeTradeStreamProvider` family** — six WebSocket connections, one per supported exchange (`BinanceTradeStreamProvider`, `CoinbaseTradeStreamProvider`, `KrakenTradeStreamProvider`, `OkxTradeStreamProvider`, `BybitTradeStreamProvider`, `BitfinexTradeStreamProvider`), all extending `AbstractExchangeStreamProvider`. Each subscribes to per-trade prints and filters by USD-value threshold (default $100k+) before persisting to the `whale_transactions` hypertable.
- **`WhaleAlertProvider`** — fallback path using the Whale Alert REST API for large-blockchain-transaction events (mint/burn, exchange address flows) that aren't captured by exchange order tape alone.
- **`WhaleAnalyticsService`** (`services/whale-service/src/main/java/com/cryptoradar/whale/service/WhaleAnalyticsService.java`) — aggregates whale trades per symbol into windowed buy/sell volume, largest-trade, average-trade-size, and an activity score normalised against a baseline of 20 trades/hour. 30-second cache TTL.
- **`WhaleMarketOverview`** — cross-symbol aggregation surfaced via the API gateway and the frontend portfolio dashboard.

The data feeds into `signal-service` as the **Whale dimension** in `MarketContext`. Buy-volume dominant → Whale dimension skews positive; sell-volume dominant → negative. The dimension contributes alongside Technical / Derivatives / Sentiment / Order Book / Macro to the overall signal score.

The empirical performance: in our v3-to-v4 outcomes window, the Whale dimension showed weak directional correlation with realised R — meaningful but smaller than the Technical and Derivatives dimensions. Two known issues affect signal quality:

1. **Trade-tape ≠ on-chain.** The whale-service captures large *exchange trades*, not on-chain wallet movements. The naming is historical; the actual signal is closer to "real-time tape-reading on the institutional end of the order book". True on-chain wallet analytics (cluster-attributed exchange in/outflows) are not in our pipeline.
2. **Cross-exchange double-counting.** A market-making firm taking a $200k arbitrage trade across Binance and Bybit at the same time appears as two $200k trades in our `whale_transactions` table. Without firm-level attribution, the "$400k of buying" reading double-counts.

## Implementation sketch (a real on-chain pipeline)

The whale-service captures one slice of on-chain analysis (exchange-side tape). A genuinely-on-chain pipeline would be a separate service:

- **New service**: `chain-data-service`. Connects to a Bitcoin/Ethereum node (Geth, Erigon, or third-party RPC provider — Alchemy, QuickNode, Infura).
- **Exchange-wallet labelling**: ingest a third-party exchange-wallet list (Glassnode publishes one; Arkham Intelligence sells one). Maintain a mutable `exchange_wallets` table keyed by address with the labelled exchange name.
- **Per-minute exchange-flow aggregation**: scan every new block, classify each transfer as "to-exchange", "from-exchange", or "between non-exchange wallets". Aggregate per-symbol net flow per minute.
- **Whale-wallet tracking**: maintain a list of wallets above (say) 1000 BTC. Generate an event row whenever such a wallet moves any meaningful balance.
- **Output**: feed two new dimensions into `signal-service`:
  - `OnChainFlow` — exchange net flow score (negative when coins flowing in, positive when flowing out).
  - `WhaleWakeup` — boolean+intensity score when a dormant whale wallet moves.
- **Compute model**: 7-day rolling z-score of exchange-flow is the right granularity (not minute-level).
- **Effort**: ≥4 weeks, dominated by the wallet-clustering data pipeline and the exchange-label maintenance. A simpler MVP using Glassnode/CryptoQuant API as a pre-computed feed (rather than running our own clustering) cuts to ~1 week but adds a vendor dependency and per-query cost.

The validation gate: before consuming any of these new dimensions in the signal score, backtest each one as a standalone predictor over our outcomes ledger and require IC ≥ 0.05 over 30 days. The current Sentiment dimension is unweighted because it didn't clear this bar; new on-chain features should be held to the same standard.

## Sources

1. **Glassnode — Documentation and Research Library.** https://docs.glassnode.com/ and https://insights.glassnode.com/ — Canonical reference for exchange-flow metrics, MVRV/NUPL/SOPR construction, and the empirical case for on-chain signals. Their methodology pages explain wallet-clustering approach.
2. **Woo, W. (2017-present). "Bitcoin On-Chain Analyses." Various articles.** https://woocharts.com — Practitioner-pioneering work on NVT ratio (a network-value-to-transactions metric), MVRV, and on-chain valuation frameworks.
3. **Puell, D. (2019). "The Bitcoin Difficulty Ribbon." On-chain Capital.** https://twitter.com/kenoshaking — Source of the difficulty-ribbon and Puell Multiple metrics; cyclical macro signals.
4. **Antonopoulos, A. M. (2017). *Mastering Bitcoin* (2nd ed.). O'Reilly Media.** https://github.com/bitcoinbook/bitcoinbook — Foundational reference for the UTXO model and how on-chain attribution heuristics work mechanically.
5. **Lischke, M., & Fabian, B. (2016). "Analyzing the Bitcoin Network: The First Four Years." *Future Internet*.** https://www.mdpi.com/1999-5903/8/1/7 — Peer-reviewed cluster-heuristic methodology paper; basis for academic exchange-flow attribution.
6. **Tasca, P., Hayes, A., & Liu, S. (2018). "The evolution of the bitcoin economy: extracting and analyzing the network of payment relationships." *Journal of Risk Finance*.** https://www.emerald.com/insight/content/doi/10.1108/JRF-03-2017-0059 — Academic treatment of the Bitcoin transaction graph as a financial network; methodological framework for the "exchange inflow" signal.
7. **Chainalysis. "Crypto Crime Report" (annual).** https://www.chainalysis.com/reports/ — Industry reference for wallet-clustering methodologies; the same techniques used for AML attribution are used for exchange-flow attribution.
