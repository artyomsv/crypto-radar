# Whale & On-Chain Tracking

> "Whale" is shorthand for an address or entity holding enough crypto that its movements affect prices. Tracking whale activity — exchange inflows, outflows, and large on-chain transfers — is one of crypto's most quoted features. It's also one of the most misinterpreted.

## Definition

There is no canonical "whale" definition. Common practitioner thresholds:

| Asset | "Whale" threshold | Practical notes |
|---|---|---|
| BTC | Address holding ≥ 1,000 BTC | ~2,000 addresses globally; many are exchange wallets. |
| ETH | Address holding ≥ 10,000 ETH | Often DeFi protocols, staking pools, exchanges. |
| ERC-20 tokens | Address with ≥ 0.1% of supply | Project treasuries dominate. |

The threshold is **per-address**, not per-entity. Coinbase splits cold storage across thousands of addresses; a single retail user with $50k can have 1 address; a market maker can hold $10B split across 200 addresses. Address-count metrics conflate scale and operational structure.

### Two distinct things called "on-chain whale tracking"

1. **Exchange flow tracking.** Watching deposits to known exchange wallets (signal: sell-pressure incoming) and withdrawals from them (signal: long-term hold or DeFi deployment). This is the most-cited and most reliable category.

2. **Large-transfer alerts.** Watching the chain for any transaction above $1M / $10M / $100M USD-equivalent. Less reliable because most large transfers are exchange-to-exchange (rebalancing), bridge events, or custody migrations — not "a whale is positioning."

### Exchange inflow vs outflow

- **Exchange inflow** = tokens moving INTO known exchange wallets. Conventionally bearish (preparing to sell on the books).
- **Exchange outflow** = tokens moving OUT of exchange wallets to self-custody / DeFi / cold storage. Conventionally bullish (long-term-hold signal).

Aggregate net exchange flow per asset per day is a standard Glassnode/CryptoQuant series. Multi-week sustained outflow has historically preceded multi-month rallies (BTC during 2020–2021, 2024 post-ETF).

### Whale Alert (the dataset)

Whale Alert (whale-alert.io) is the most widely-known consolidated feed: a paid API that pushes JSON events for every transaction above a configurable USD threshold across 30+ chains. They label transactions with known-entity tags ("Binance", "Coinbase", "Tether Treasury", "Unknown wallet"). The feed is the standard input for retail-facing whale-tracking products.

## When it works

- **Exchange-balance regime indicators.** Multi-week aggregate exchange-held BTC trending down = structural bid > sell. Trending up = sell-side staging. The signal is slow (week+ horizon) but consistent.
- **Specific entity-watched movements.** When a known cohort (e.g. early Bitcoin whales, ETH foundation, ETF issuers) moves significantly, it's worth knowing. Genesis bankruptcy-era token movements telegraphed several large dumps.
- **Stablecoin treasury movements.** Tether Treasury minting USDT to a fresh address followed by transfer to Binance is the most-tracked specific event — usually precedes new fiat-side buying activity.
- **Confirmation, not primary signal.** Whale outflow alongside funding flipping positive, OI rising, and dominance rising is much stronger than any single signal.

## When it fails

- **Exchange wallet relabeling.** Exchanges rotate wallets. A transfer from "Coinbase wallet A" to "Coinbase wallet B" (internal rebalancing) looks like a "whale outflow" to a labeling system that hasn't caught up.
- **Custodian flows ≠ owner intent.** A 5,000 BTC transfer from Coinbase Custody to BlackRock's IBIT cold storage is an ETF subscription movement, not a "whale planning to buy." Mis-classifying it inflates whale-outflow bullishness.
- **Self-custody normalization.** Post-FTX, retail and institutions both moved more to self-custody. Sustained 2023 outflow was partly a one-time structural shift, not an ongoing bullish accumulation.
- **Bridge events.** A token moving from Ethereum to Arbitrum via a bridge looks like two transactions: deposit to bridge (inflow) + mint on destination chain (which may register as "from unknown" depending on the labeler). One real movement, two recorded events.
- **OTC desks bypass on-chain.** Large institutional trades route through OTC desks that net internally — never touching the public chain. On-chain whale data systematically under-counts the largest flows.
- **Front-running / wash signals.** Once a whale-watching account becomes influential, sophisticated traders can move tokens specifically to trigger its alerts and induce retail to follow. The signal becomes the manipulation.

## What we do today (in projectr-x)

`whale-service` (port 31084) is the dedicated subsystem. Architecture:

- **6 exchange WebSocket flows** subscribed simultaneously. The 6 venues are Binance, Coinbase, Kraken, OKX, Bybit, and one rotating slot (historically Bitfinex). Streams: `trade` (per-fill) and aggregated trade volume.
- **Filter**: large-fill detection (per-symbol threshold tuned to "≥ 4× p95 of last 1h trade size on that symbol/venue"). Persistent threshold tuning lives in `whale-service` config.
- **Aggregation**: per-symbol, per-minute large-fill count + USD-volume, written to `whale_flows` table.
- **Dimension contribution**: `signal-service` reads recent whale-flow aggregates and contributes to the `Whale` dimension in `SignalEngine`'s 6-dimension scorer.

We do **not** currently use Whale Alert's API or on-chain wallet labeling. The reason: our trading horizon is short (minutes to hours), and on-chain exchange-flow signals have multi-day latency. Exchange WS `trade` flows arrive in real-time and are the right input for our cadence.

Implementation notes:

- The CLAUDE.md confirms `whale-service` tracks "6 exchange WebSocket flows" without specifying providers. Reconcile against `services/whale-service/src/main/java/com/cryptoradar/whale/provider/` before quoting specific venue names externally.
- `Whale` dimension contributions can be inverted on illiquid pairs — a sudden $100k order on LINKUSDT moves the L/S balance more than a $10M order on BTCUSDT, but the *signal* meaning is opposite (liquidity event vs aggressive flow). The detector applies per-symbol normalization to handle this.

### Why we don't ingest Whale Alert today

The free tier provides ≤ 1 transaction every ~5 minutes — useless for our cadence. The paid tier ($30–$500/month depending on rate limits) provides real-time deltas with entity tags. We've punted the integration until on-chain wallet labels become a documented input feature, because:

1. The biggest signals (exchange withdrawals) are already partially captured by our exchange-side WS flow.
2. Wallet-labeling quality is the limiting factor; investing in our own label pipeline is unfunded.
3. Adding a low-confidence feature to a scoring stack we just stabilized (v4) would muddy attribution.

If/when added, the integration is straightforward: `WhaleAlertClient` reads the WS, filters to assets in our 13-pair universe, writes to `on_chain_whale_events` table, exposes 1h aggregate count + USD-vol to the `Whale` dimension as an additional input alongside exchange flow.

## Sources

1. **Whale Alert API documentation.** https://docs.whale-alert.io/ — REST + WS contract, entity labels, supported assets. Free tier limited to historical data.
2. **Glassnode "Entity-Adjusted Metrics" methodology.** https://insights.glassnode.com/the-week-onchain-week-20-2022/ — How Glassnode clusters addresses into entities (the basis for "true" whale tracking vs raw address tracking).
3. **CryptoQuant Exchange Flow indicators.** https://cryptoquant.com/asset/btc/chart/flow-indicator — Industry-standard exchange-inflow/outflow series. Free dashboards; paid API for systematic integration.
4. **Chainalysis (2024), "Crypto Crime Report."** https://www.chainalysis.com/crypto-money-laundering/ — Methodology for distinguishing legitimate flows from illicit; the underlying entity-clustering work informs labeled-wallet quality.
5. **Coin Metrics, "Network Data Pro" methodology.** https://docs.coinmetrics.io/asset-metrics/network — Definitions for active addresses, transfer counts, supply distribution by holding cohort.
6. **Lopez-Cabarcos et al. (2022), "Are stablecoin flows useful for predicting Bitcoin returns?"** — Related but distinct; uses exchange-flow series as inputs (see `04-stablecoin-flows.md`).
