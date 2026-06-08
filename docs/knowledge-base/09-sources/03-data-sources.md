# Data Sources — APIs We Use, APIs We Could Use

> Every signal projectr-x produces is downstream of a data feed. This is the operational inventory: what we consume, where it lives in code, what's free, what's paid, what the rate limits are, and what we'd add next.

## What we consume today

### Bybit V5 — execution, account state, contract reference

**Used for.** Live trading. Place / cancel orders, set trailing stops (`setTradingStop`), read positions, read wallet balance, fetch closed-PnL history, instrument-info for tick/lot sizes.

**Code paths.**
- REST: [`services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java`](../../services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java) — all signed calls, endpoint resolver (linear vs spot via `categoryFor`), client retry/backoff.
- WebSocket: [`services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5WsClient.java`](../../services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5WsClient.java) — private streams (position, execution, order), drives the live-state machine.
- Signer: [`BybitV5Signer.java`](../../services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5Signer.java) — HMAC-SHA256 signing per V5 spec.
- Endpoints: [`BybitV5Endpoints.java`](../../services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5Endpoints.java) — DEMO vs MAINNET base URLs.

**Pricing & limits.** REST: free with API key, 600 requests / 5 sec per endpoint family on V5 for authenticated calls. WS: free, single connection per account. Public market-data endpoints (kline, tickers, orderbook) require no auth but share IP-based rate limits.

**Docs.** <https://bybit-exchange.github.io/docs/v5/intro>

**Gotchas.** Bybit's `category` parameter (`linear` vs `inverse` vs `option` vs `spot`) is required on almost every endpoint. Our `BybitV5RestClient.categoryFor(symbol)` resolves USDT-suffix → `linear`. Time-windowed matching in `OrderReconciler.pickMatchingClose` exists because Bybit's closed-PnL history can return entries from prior repeat trades on the same symbol if the time window is unconstrained.

### Binance Spot — primary price/kline source for signal generation

**Used for.** 1m, 4h, 1d kline series for every tracked symbol. The source of truth for `MarketContext.recent4hBars()` and the price reference for entry/stop math in `LiquiditySweepDetector` and `TrendContinuationDetector`. Spot tickers used as `currentPrice` in `OutcomeEvaluator` walk-forward simulation.

**Code paths.**
- [`services/market-data-service/src/main/java/com/cryptoradar/marketdata/client/BinanceClient.java`](../../services/market-data-service/src/main/java/com/cryptoradar/marketdata/client/BinanceClient.java) — REST kline + tickers.
- [`BinanceRateLimiter.java`](../../services/market-data-service/src/main/java/com/cryptoradar/marketdata/client/BinanceRateLimiter.java) — client-side weight tracking to stay under Binance's 6000-weight/min REQUEST_WEIGHT limit.

**Pricing & limits.** REST is free. `GET /api/v3/klines` has weight 2 per call. The 6000-weight-per-minute IP cap supports ~3000 kline calls/min. Trading endpoints (which we don't use here, since execution is on Bybit) have separate rate limits.

**Docs.** <https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints>, <https://developers.binance.com/docs/binance-spot-api-docs/rest-api/limits>

### Binance Futures — derivatives dimension data

**Used for.** Funding rate history, open interest history, long/short ratio (`globalLongShortAccountRatio`, `topLongShortPositionRatio`), aggregated funding-rate index.

**Code paths.** [`services/derivatives-service/src/main/java/com/cryptoradar/derivatives/client/BinanceFuturesClient.java`](../../services/derivatives-service/src/main/java/com/cryptoradar/derivatives/client/BinanceFuturesClient.java).

**Pricing & limits.** Free. Subject to the futures-side `X-MBX-USED-WEIGHT-1m` budget which is separate from spot.

**Docs.** <https://developers.binance.com/docs/derivatives/usds-margined-futures/general-info/general-information>

### Bybit + OKX — liquidation streams

**Used for.** Real-time forced-liquidation prints. The `Derivatives` dimension uses liquidation-cluster intensity as a contrarian / capitulation signal.

**Code paths.**
- [`services/derivatives-service/src/main/java/com/cryptoradar/derivatives/provider/BybitLiquidationProvider.java`](../../services/derivatives-service/src/main/java/com/cryptoradar/derivatives/provider/BybitLiquidationProvider.java)
- [`OkxLiquidationProvider.java`](../../services/derivatives-service/src/main/java/com/cryptoradar/derivatives/provider/OkxLiquidationProvider.java)

**Pricing & limits.** Free. WebSocket only — REST polling for liquidations is impractical at the resolution we need.

**Docs.** <https://bybit-exchange.github.io/docs/v5/websocket/public/liquidation> and <https://www.okx.com/docs-v5/en/#public-data-websocket-liquidation-orders-channel>

### Whale Alert — large on-chain transfers

**Used for.** Cross-chain large-transfer alerts (≥$1M default threshold). Feeds the `Whale` dimension as a context signal — large wallet movements toward exchanges precede selling pressure.

**Code paths.** [`services/whale-service/src/main/java/com/cryptoradar/whale/provider/alert/`](../../services/whale-service/src/main/java/com/cryptoradar/whale/provider/alert) — Whale Alert REST client and consumer.

**Pricing & limits.** Tiered. Free tier limited to 10 requests/min and only $1M+ transactions; paid tiers go to higher resolution and websocket streaming. Requires `WHALE_ALERT_API_KEY` (per project `.env.example`).

**Docs.** <https://developer.whale-alert.io/documentation/>

### Exchange WebSocket fan-out — spot deposit/withdrawal streams

**Used for.** Per-exchange spot-wallet inflow/outflow inference for the `Whale` dimension. Six venues monitored.

**Code paths.** [`services/whale-service/src/main/java/com/cryptoradar/whale/provider/`](../../services/whale-service/src/main/java/com/cryptoradar/whale/provider) — separate provider per venue: `binance/`, `bitfinex/`, `bybit/`, `coinbase/`, `kraken/`, `okx/`. Each implements the same `ExchangeProvider` interface.

**Pricing & limits.** Free per-venue WebSocket access. No aggregation cost.

### CoinDesk + RSS — news + sentiment

**Used for.** News headlines for the `Sentiment` dimension; per-symbol sentiment scoring via a downstream sentiment model. Re-enabled in v4 (`G.2`) for trading pairs after a bug had silently disabled it.

**Code paths.** `services/news-service/` (full module). Stores headlines in the `postgres` (not TimescaleDB) database since news is metadata-shaped, not time-series-shaped.

**Pricing & limits.** RSS feeds are free with reasonable polling intervals (~5 min). CoinDesk doesn't expose a public API officially; we consume the RSS feed.

## What we could use (not currently integrated)

### Deribit — crypto options reference

**Why we don't use it yet.** Options-derived signals (25-delta skew, term-structure slope, RV-IV gap) are non-redundant with the 6 existing dimensions but require a separate microservice and a vol-surface model. Deferred until v4/v5 signal data is mature.

**Effort to integrate.** New `options-service` polling Deribit's public `/api/v2/public/get_book_summary_by_currency` and `/api/v2/public/get_volatility_index_data` on a 5-min cadence. No auth needed for reference data. A new `Volatility` dimension scorer in `signal-service`.

**Docs.** <https://docs.deribit.com/>

### CoinGecko — broad coin-universe market data

**Why we'd add it.** When we expand beyond our current 13-symbol universe, CoinGecko's `/coins/markets` endpoint gives a one-shot snapshot of market cap, 24h volume, price changes for thousands of coins. Useful as a universe-screening input (e.g., "only consider top 100 by market cap").

**Pricing & limits.** Free Demo tier: ~30 req/min (variable), 10,000 calls/month cap. Paid tiers from $35/mo (300 req/min). API keys recommended — without a key, the IP-based limit is variable and unreliable.

**Docs.** <https://docs.coingecko.com/>, <https://www.coingecko.com/en/api/pricing>

### CoinMarketCap — alternative universe data

**Why we'd add it.** Backup / cross-check to CoinGecko. Pro API has tighter rate limits but a more stable / less-rotating data structure.

**Pricing & limits.** Free Basic tier: 333 calls/day, very limited endpoints. Pro tier starts $79/mo for 10k/day.

**Docs.** <https://coinmarketcap.com/api/>

### Glassnode — on-chain metrics

**Why we'd add it.** Glassnode's on-chain cohort metrics (MVRV, SOPR, Realized Cap, LTH/STH supply, exchange netflows at the protocol level) are stronger long-horizon regime indicators than anything in our current dimension stack. Their Week-On-Chain publication explicitly tracks the metrics we'd want.

**Pricing & limits.** Free for basic time-series, paid Advanced/Pro tiers for institutional-grade metrics. The `tier 3+` metrics (e.g., URPD, cohort SOPR) sit behind their professional plans.

**Docs.** <https://docs.glassnode.com/>, <https://insights.glassnode.com/>

### CryptoQuant — exchange flows + on-chain stress signals

**Why we'd add it.** Exchange inflow/outflow at the protocol layer (not our current per-wallet inference), miner-position-index, stablecoin supply ratio. Specifically: their netflow-by-exchange metric is the cleanest equivalent of what our `Whale` dimension tries to approximate.

**Pricing & limits.** Free tier has limited resolution. Premium starts at $39/mo (Advanced) up to $499/mo (Professional). API requires an active developer subscription.

**Docs.** <https://cryptoquant.com/docs>, <https://userguide.cryptoquant.com/api/introduction>, <https://userguide.cryptoquant.com/api/btc-exchange-flows>

### Kaiko — institutional market data

**Why we'd consider it.** Kaiko aggregates clean tick data across CEX/DEX venues with wash-trading hygiene applied. Their data is the closest to "ground truth" market microstructure available outside of building a custom multi-venue ingestor.

**Pricing & limits.** Institutional-only, ~$5-50k/year depending on coverage. Likely out of scope for our budget but worth knowing about.

**Docs.** <https://docs.kaiko.com/>

### CME Group — institutional BTC/ETH futures

**Why we'd consider it.** CME futures basis is a closely-watched institutional positioning signal. The Friday-3pm-CT expiry CME settlement is referenced widely in macro-crypto commentary.

**Pricing & limits.** Market data is paid via direct feed or aggregators. Free historical settlement data via cmegroup.com.

**Docs.** <https://www.cmegroup.com/markets/cryptocurrencies.html>

## Operational notes

### Rate-limit hygiene

Every external API has a rate limit. Our `BinanceRateLimiter` is the only client-side limiter currently in code; Bybit calls rely on backoff-on-429 rather than predictive limiting. This is acceptable at our current volume but will need refinement as we grow:

- Per `~/.claude/rules/observability-and-logging.md`, every external API call should emit a structured log line including endpoint, latency, and response status.
- Per `~/.claude/rules/secrets-and-env-handling.md`, every API key reads with `@ConfigProperty` (no default) so missing keys fail-fast at startup.

### Costs and decisions

The total monthly external-API cost at current scale is ~$0 — we run almost entirely on free tiers. The pre-paid Whale Alert subscription is the largest line item. Premium data (Glassnode, CryptoQuant) would add $40-500/mo per source — justified only when we have evidence the signal is incremental over what's free.
