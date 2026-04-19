# Silent delisting detection gap in candle scheduler

| Field        | Value                                                                                              |
|--------------|----------------------------------------------------------------------------------------------------|
| Criticality  | High                                                                                               |
| Complexity   | Small                                                                                              |
| Location     | `services/market-data-service/src/main/java/com/cryptoradar/marketdata/service/MarketDataService.java` |
| Found during | Debugging "latest candles missing" — XMR was silently frozen for 2+ years                          |
| Date         | 2026-04-08                                                                                         |

## Issue

When Binance delists a trading pair, their klines API does **not** return an error or an empty array — it returns **one stale kline frozen at the delisting moment**, and keeps returning that same kline forever. Our scheduler "successfully" stores the same 3 bars every minute, the upsert overwrites the same row in place, the log line says `Stored 3 candles for XMRUSDT [1m]` as usual, and nobody notices that the `time` column of that row hasn't advanced in years.

This is exactly what happened with XMRUSDT:

- Binance delisted Monero on **2024-02-20 02:59 UTC** (EU privacy-coin regulatory pressure).
- Every candle for XMR in our DB has `time <= 2024-02-20`.
- The scheduler has been fetching XMRUSDT 14+ times per minute (across all 11 intervals) for **2+ years**, burning API quota and log space, with zero new data.
- A user only noticed when they opened the Trade Chart Modal for a symbol and saw "nothing fresh at the right edge." Without that specific UI flow, the rot would have kept growing.

The current diagnosis path took manual SQL per-symbol staleness queries to find the problem. There is no automated detection, no alarm, no metric, no log event that says "XMR is stuck."

## Risks

1. **Undetected data rot.** Any future delisting (Binance delists pairs regularly — entire batches of low-cap alts, privacy coins, meme tokens under regulatory pressure) will silently freeze that symbol's data until someone manually notices. During that window, any signal computed against that symbol is operating on fictional current prices.
2. **Wasted API quota and log noise.** Every dead symbol consumes ~11 HTTP calls per minute (one per interval), rate-limiter capacity, and log lines that look identical to healthy fetches. If we added 10 more symbols and 3 got delisted, we'd waste 30% of our Binance quota on ghost fetches.
3. **Backtest corruption.** Historical backtests that include the delisting window will see frozen prices immediately after the delisting moment, generating nonsensical "flat" OHLCV and breaking every technical indicator downstream of them.
4. **Outcome tracker false positives.** Detectors that fire on a stale symbol will never see their stop or target hit because the price literally never moves. The outcome sits `PENDING` for 7 days, then `EXPIRED` at the same flat price. Metrics show a dead hit rate for the "strategy" rather than recognizing a dead symbol.

## Suggested solutions

Implement in order — each is cheap and each adds independent defense:

1. **Log WARN when a fetch returns a kline older than the previous fetch (or same age).**
   Inside `MarketDataService.upsertCandles()`, compare the newest incoming `candle.time` against the maximum existing time for that `(symbol, interval)`. If they're equal for N+ consecutive fetches, log a WARN `Symbol %s [%s] appears stale (latest bar unchanged for %d fetches)`. ~15 lines in the upsert path.

2. **Per-symbol staleness metric.**
   Publish a gauge `market_data_symbol_staleness_seconds{symbol=,interval=}` computed as `now() - max(time)`. The scheduler already runs every minute, it can update the metric at the end of each fetch loop. Hook it into the existing `/q/health` endpoint so an external health probe can fail on stale symbols. ~30 min of work.

3. **Auto-deactivate after N consecutive stale fetches.**
   If a symbol+interval combination has been stale for, say, 3 hours of continuous fetches, flip `crypto_assets.is_active=false` automatically and log a single INFO line. Fail-safe — no human needed to notice a delisting. Takes a small state cache in the scheduler (`Map<String,Integer> consecutiveStaleFetches`). ~45 min of work.

4. **Add a startup data-quality check.**
   On service start, query `SELECT symbol FROM candles WHERE interval='1m' GROUP BY symbol HAVING MAX(time) < NOW() - INTERVAL '1 hour'`. If any rows, log WARN for each and/or auto-deactivate them. This catches any rot that accumulated while the service was down. ~10 min of work.

## Immediate data fix already applied

- `UPDATE crypto_assets SET is_active=false WHERE symbol='XMRUSDT';` — run against the live DB.
- `db/init/timescaledb-init.sql` — removed XMRUSDT from the bootstrap INSERT with a comment explaining why.
- `devops/overlays/dev/marketdata-db-init-sql.yaml` — same.
- Verified: scheduler no longer fetches XMR (13 fetches per interval per cycle instead of 14), all other symbols fresh to within 30 seconds.

## Remaining XMR code references (not cleaned up for scope reasons)

These are **harmless as long as `crypto_assets.is_active=false`** because all four services read their tracked-symbol list from the DB first, only falling back to hardcoded defaults if the DB is unreachable. Cleaning them up would require rebuilding 4 backend services and touching WebSocket subscription configs. Tracked here so a future full cleanup can find them:

- `services/market-data-service/src/main/java/.../BinanceClient.java:37` — `DEFAULT_SYMBOLS` fallback constant
- `services/derivatives-service/src/main/java/.../BinanceFuturesClient.java:39` — same pattern
- `services/analytics-service/src/main/java/.../AnalyticsService.java:29` — same pattern
- `services/whale-service/src/main/java/.../WhaleAnalyticsService.java:26` — same pattern
- `services/whale-service/src/main/java/.../provider/*/` — 6 exchange stream providers (OKX, Kraken, Coinbase, Bitfinex, Binance, Bybit) with hardcoded XMR subscriptions
- `services/whale-service/src/main/java/.../AbstractExchangeStreamProvider.java:36` — `TIER_MID` set
- `services/api-gateway/src/main/java/.../AggregationService.java:38` — symbol-to-name mapping
- `frontend/src/types/index.ts:117,134` — UI label and icon constants
