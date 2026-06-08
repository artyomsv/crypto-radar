# Spot vs Derivatives in Crypto

> Where the volume actually is, why it matters for signal design, and what each venue type implies for execution.

## Definition

Crypto markets are not a monolith. The same underlying asset (BTC, ETH, etc.) trades in at least four structurally distinct venue types, and the prices in each are linked by arbitrage but not identical at any given moment.

- **Spot markets** trade the asset itself for immediate (T+0) settlement. Buy BTC on Coinbase spot, you own BTC. No leverage, no funding, no expiry. The "honest" price reference in the sense that nothing about the instrument's design contributes to the print.
- **Dated futures** are agreements to deliver an asset on a fixed future date. CME Bitcoin futures (quarterlies) and Binance/OKX dated contracts. Cash- or physically-settled. Price converges to spot at expiry via no-arbitrage. The basis (futures price minus spot price) carries an implied cost-of-carry and risk premium.
- **Perpetual swaps (perps)** are the dominant crypto-native instrument: futures with no expiry, anchored to spot via a periodic funding payment between longs and shorts. Bybit, Binance Futures, OKX, Hyperliquid all run perps as their flagship contract. Discussed in depth in `02-perpetual-swaps.md`.
- **Options** are rights (not obligations) to buy or sell at a strike by an expiry. Deribit dominates (~85% of crypto options volume through 2025), with CME, Bybit, and Coinbase-owned Deribit growing. See `03-options-mechanics.md`.

## The volume picture

The 2025 numbers settled an old debate decisively: derivatives, and specifically perpetuals, are where price discovery and capital live in crypto. The top 10 perpetual venues alone processed about **$92.9 trillion in 2025 trading volume**, dwarfing spot across all exchanges combined. Spot CEX volume contracted 27.7% quarter-on-quarter in Q3 2025 even as perps grew — capital is migrating to leveraged instruments, not adding them on the side. ([CoinGecko: rise of perp DEXs](https://www.coingecko.com/learn/rise-of-perpetuals-and-perp-dexs); [ainvest: 2025 CEX perpetual swap boom](https://www.ainvest.com/news/2025-cex-perpetual-swap-boom-structural-shift-crypto-trading-demand-2601/))

A rough but useful share: roughly **70% of total crypto trading volume is on perpetuals**, ~20-25% on spot, ~5% on options and dated futures combined. The ratio drifts with regime — bear-market deleveraging compresses perp share, late-cycle blow-offs widen it — but the order of magnitude has been stable since 2022.

## Why this matters for projectr-x

Signal generation that treats spot prices as ground truth and ignores derivatives data is reading the smaller of two markets. Every dimension we score except `Technical` and `Order Book` is essentially a derivatives-market read:

- `Derivatives` dimension — funding rate, open interest, long/short ratio, liquidations. These come from `derivatives-service` (Binance Futures via `BinanceFuturesClient`, Bybit/OKX liquidations via the provider package).
- `Whale` dimension — flows between spot exchange wallets, but interpreted in the context of perp positioning. A large spot inflow during high positive funding tells a different story than the same inflow during negative funding.
- The trade-execution side runs entirely on Bybit V5 USDT-perps (`BybitV5RestClient`). We do not trade spot at all — partly because perp leverage is the user's typical use case, partly because perp liquidity is consistently 3-5× deeper.

The execution choice is deliberate: routing signal-side detector outputs to perp orders gets us inside the venue where most of the market participates, with funding as a controllable cost and stop management as a native exchange feature.

## When the spot/perp distinction matters operationally

- **During funding rate extremes.** Spot price can rally hard while perps face a punishing 0.1-0.3%/8h funding payment that drains long PnL. A long signal that looks great on the spot chart is a different trade after 24 hours of negative carry. The `Derivatives` dimension catches this.
- **Around big spot ETF flows.** BlackRock IBIT inflows distort the spot side more than perp positioning — meaning the perp basis and funding signals can lag the actual buying pressure visible in spot order books and on-chain inflows to ETF custodians.
- **In delistings or low-liquidity perps.** Spot can keep printing on small CEXes after a perp is delisted from major venues, leaving stale derivatives data. We hit this with XMRUSDT (Binance delisted spot 2024-02-20, futures still served frozen kline data — dropped from our universe).

## When it doesn't matter

- For very short-horizon TA on liquid majors (BTC, ETH), spot and perp prices track within bps and the signal is the signal. Detectors using 4h candles see effectively the same chart on either venue.
- For longer-horizon trend signals, the SMA50 > SMA200 condition in `TrendContinuationDetector` is identical on Binance spot and Binance perp for any pair we trade — they cross within the same daily bar.

## What we do today

- **Signal generation reads spot prices and 4h candles from Binance via `market-data-service`** (`BinanceClient.java`). Spot is the price reference for entry/stop math and the kline series fed into `IndicatorCalculator`.
- **Derivatives signals are aggregated from multiple venues.** `BinanceFuturesClient` for funding/OI/long-short, `BybitLiquidationProvider` and `OkxLiquidationProvider` for forced-liquidation streams. The dimension scoring combines these into the `Derivatives` score consumed by both detectors.
- **Execution runs only on Bybit V5 USDT perps** via `BybitV5RestClient` and `BybitV5WsClient`. There is no spot execution path. Stop-losses are placed natively on Bybit (not local), with a TrailMirror process updating them as the signal-side trail ratchets.
- **Whale flows are spot-side.** The six-exchange WebSocket fan-out in `whale-service` (`provider/binance`, `provider/coinbase`, `provider/kraken`, etc.) monitors deposits and withdrawals from spot exchange wallets — meaningful primarily because spot inflows precede perp-side selling pressure.

## Sources

1. [CoinGecko — The Meteoric Rise of Perp DEXs](https://www.coingecko.com/learn/rise-of-perpetuals-and-perp-dexs) — $92.9T 2025 perp volume across top 10; DEX-to-CEX perp ratio from 2.1% (Jan 2023) to 11.7% (Nov 2025).
2. [ainvest — 2025 CEX Perpetual Swap Boom](https://www.ainvest.com/news/2025-cex-perpetual-swap-boom-structural-shift-crypto-trading-demand-2601/) — quarter-on-quarter spot contraction (-27.7% Q3 2025) alongside perp growth.
3. [CoinMarketCap — Cryptocurrency Derivatives Market Data](https://coinmarketcap.com/charts/derivatives-market/) — live derivatives volume share by venue.
4. [Sherwood News — Perpetual futures grow beyond crypto](https://sherwood.news/crypto/perpetual-futures/) — context on perps spreading from crypto into adjacent asset classes; useful framing.
5. [CoinGlass 2025 Derivatives Annual Report](https://www.coinglass.com/learn/2025-annual-report-en) — venue-by-venue breakdown, open interest evolution.
6. [Datawallet — Perpetuals vs Spot Trading Explained](https://www.datawallet.com/crypto/perpetual-vs-spot-trading-in-crypto-explained) — accessible primer if onboarding new contributors.
