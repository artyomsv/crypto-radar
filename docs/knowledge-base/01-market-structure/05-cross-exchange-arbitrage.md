# Cross-Exchange Arbitrage

> Triangular and cross-venue arbitrage exist as documented inefficiencies in crypto, but the speed game is owned by HFT shops with co-located infrastructure. Retail's edge, if any, lives in rare large dislocations and capital-mobility constraints, not in latency.

## Definition

Three related but distinct strategies share the "arbitrage" label:

- **Triangular arbitrage** — within a single exchange, exploit price inconsistencies among three pairs. `BTC/USDT * USDT/USDC * USDC/BTC` should equal exactly 1. When the product diverges, a synchronous three-leg trade locks in the difference. Inefficiencies here close in milliseconds on liquid CEXes.
- **Cross-exchange arbitrage (latency arb)** — same asset, different venue, different price for a brief window. Buy where it's cheap, sell where it's expensive. On crypto this works on ~100-500 ms windows (per practitioner sources) versus 50-200 ms in FX, because crypto venue infrastructure varies more widely. ([CoinAPI latency arbitrage glossary](https://www.coinapi.io/learn/glossary/latency-arbitrage))
- **Statistical / spread arbitrage** — pairs trading, basis trades (perp-spot), funding-rate arbitrage. Slower-moving, edge-and-risk lives in the mean-reversion of a spread.

The first two are *the* high-frequency strategies on crypto. The third is closer to what projectr-x would build into if we extended into multi-leg strategies.

## Why retail can't compete on speed

The latency stack required to win at cross-exchange arb at the 100ms tier:

1. **Co-located servers** in the same data centers as exchange matching engines (AWS Tokyo for Binance, AWS Singapore for OKX, AWS Frankfurt for Bybit, etc.). The exchange's "low-latency" tier is open to anyone willing to pay for the rack.
2. **WebSocket streaming feeds** with custom binary protocols (FIX-style) rather than JSON-over-WebSocket. The ms savings on parse time matters when book updates come at 1 kHz.
3. **Proprietary risk engines** that pre-approve trades on bursty signals without round-tripping a database. Risk decisions cannot live behind a network hop.
4. **Inventory pre-positioning.** You don't buy on Binance and *then* sell on Bybit — you keep capital on both sides and rebalance via withdrawals on a slow schedule. Crypto withdrawal latency (minutes to hours) is the kill-shot for "buy cheap, withdraw, sell dear" plays.

Practitioner sources are unanimous: the field is now mature enough that the floor of latency-arb profitability has been bid down to the point where only co-located HFT shops cover their costs. ([PocketOption cross-exchange latency arbitrage](https://pocketoption.com/blog/en/knowledge-base/trading/latency-arbitrage/); [BJF Trading — Does Retail Have a Chance in Arbitrage?](https://bjftradinggroup.com/does-retail-have-a-chance-in-arbitrage/))

## Where retail still wins

There's a narrow band of cross-exchange opportunities that survive the HFT compression:

- **Idiosyncratic listings.** When a small CEX lists a coin that majors don't, the price can disconnect from the broader market for hours. Retail with patience can take the bait — but counterparty risk on small CEXes is real (Bittrex, FTX, Vauld... the list of exchanges that vaporized client funds is long).
- **Sudden venue-specific events.** Maintenance windows, gateway congestion, or socket-disconnect storms can leave one venue's price stuck for minutes. If you happen to have inventory on the correctly-priced side, the play is to trade *into* the broken venue, not against it.
- **Capital-mobility friction trades.** Some venues (regional ones, sanctioned ones, restricted-jurisdiction ones) trade at structural premia or discounts because moving capital in or out is hard. The premium isn't a free lunch — it's a payment for someone willing to bear the inventory risk. This is closer to lending than to arbitrage.
- **Funding-rate arb.** Take a long basis position (spot BTC + short perp) when funding is high and persistent. The funding-rate "yield" is structurally positive on average. This is a slow-money strategy, not HFT, and works in size with normal retail infrastructure.

## What we do today

Cross-exchange arbitrage is **not part of the projectr-x signal-or-execution stack**. The architecture is single-venue execution (Bybit V5 USDT-perps) with multi-venue *signal* inputs:

- Funding/OI/LS ratios are aggregated from Binance Futures via `BinanceFuturesClient`.
- Liquidation streams aggregate Bybit + OKX in `derivatives-service/provider/`.
- Whale flow streams six spot venues (Binance, Bitfinex, Bybit, Coinbase, Kraken, OKX) in `whale-service/provider/`.

Using cross-venue *data* to inform a *single-venue* execution decision is qualitatively different from arbitraging cross-venue *prices* — and it's the right architecture for a signal-driven system. The expected edge is signal quality on perp execution, not capturing 0.5-bps dislocations.

## When cross-exchange "arb" goes wrong

- **Sweep risk.** You see BTC at $100k on Binance, $100.05k on Coinbase. By the time your taker order lands on Binance, the price is $100.05k everywhere. You bought at the new mid, the dislocation is gone, you eat the spread plus 2× taker fees on the round trip. This is the modal outcome.
- **Stuck funds.** You buy on Exchange A intending to withdraw and sell on Exchange B. Exchange A pauses withdrawals citing "wallet maintenance" for 36 hours. Exchange B's premium has evaporated by the time funds arrive. You're now long crypto inventory you didn't want.
- **Counterparty failure.** The premium on a small venue is often the market pricing in solvency risk. Trading into the premium without independent diligence on the venue is selling insurance against the venue blowing up — frequently at a worse risk-reward than the trade looks.
- **Tax treatment.** Multi-venue rotation generates more taxable events per dollar of edge than single-venue trading. In some jurisdictions this drains a significant fraction of nominal alpha.

## Reading list

1. [PocketOption — Cross-Exchange Latency Arbitrage Strategies](https://pocketoption.com/blog/en/knowledge-base/trading/latency-arbitrage/) — practitioner overview of latency requirements and venue-dependent profit windows.
2. [CoinAPI — Latency Arbitrage Glossary](https://www.coinapi.io/learn/glossary/latency-arbitrage) — clean definition with the 100-500 ms crypto window number.
3. [BJF Trading — Does Retail Have a Chance in Arbitrage?](https://bjftradinggroup.com/does-retail-have-a-chance-in-arbitrage/) — honest assessment from a practitioner: the answer is mostly no on latency, sometimes yes on capital-mobility plays.
4. [Medium — Jung-Hua Liu, High-Frequency Arbitrage and Profit Maximization Across Cryptocurrency Exchanges](https://medium.com/@gwrx2005/high-frequency-arbitrage-and-profit-maximization-across-cryptocurrency-exchanges-4842d7b7d4d9) — quantitative treatment with actual P&L numbers from a working pipeline.
5. [Bitsgap — Crypto Arbitrage Explained](https://bitsgap.com/blog/crypto-arbitrage-explained-tutorial) — accessible primer; useful to understand the retail-tool landscape and what those tools claim vs deliver.
6. Chan, *Algorithmic Trading* — Chapter on statistical arbitrage and pairs trading; canonical for slower-moving spread strategies. See `09-sources/01-books.md`.
