# Bybit V5 API

> The V5 API is Bybit's unified surface across spot, perpetual, options, and inverse contracts. It's the only exchange API this project trades against. Understanding its rate limits, order types, position modes, and signature scheme is non-negotiable for anything that touches `trade-execution-service`.

## Definition

### REST surface

Base URL by environment:
- **Mainnet**: `https://api.bybit.com`
- **Demo trading**: `https://api-demo.bybit.com` (paper-trading on real market data — what we use for testing)

Endpoint families (all `/v5/...`):
- **Market** — `tickers`, `kline`, `orderbook`, `instruments-info`, `funding/history`, `open-interest`, `recent-trade`. Public, no auth.
- **Order** — `create`, `cancel`, `amend`, `realtime` (open orders), `history`. Requires authentication.
- **Position** — `list`, `set-leverage`, `set-tpsl-mode`, `trading-stop`. Authentication required.
- **Account** — `wallet-balance`, `info`, `transactions-log`. Authentication required.
- **Asset** — `transfer`, `withdraw`, `coins/balance`. Auth required; withdraw permission must be enabled on key.

### WebSocket surface

- **Public stream**: `wss://stream.bybit.com/v5/public/{category}` (linear, spot, option, inverse). Subscriptions: `tickers.{symbol}`, `kline.{interval}.{symbol}`, `orderbook.{depth}.{symbol}`, `publicTrade.{symbol}`.
- **Private stream**: `wss://stream.bybit.com/v5/private` (or `wss://stream-demo.bybit.com/v5/private`). Auth via signed `op:auth` first frame. Topics: `position`, `order`, `execution`, `wallet`, `greeks`.
- **Trade WS**: `wss://stream.bybit.com/v5/trade`. Place/amend/cancel orders over WS — lower latency than REST, useful for high-frequency setups.

### Rate limits

- **Default**: 600 requests per 5-second window per IP across all endpoints to `api.bybit.com`. Exceeding produces HTTP 403 ("access too frequent") followed by a 10-minute ban.
- **Per-endpoint UID limits**: order placement is bucketed separately (~10–20 orders/sec depending on VIP tier). Returned in headers (`X-Bapi-Limit`, `X-Bapi-Limit-Status`, `X-Bapi-Limit-Reset-Timestamp`).
- **WebSocket**: max 500 subscriptions per connection. Reconnect on disconnect with exponential backoff; Bybit will rate-limit re-auth attempts if you reconnect tighter than once per 5s.

### Order types and timeInForce

| `orderType` | `timeInForce` | Behavior |
|---|---|---|
| `Market` | implicit IOC | Fill immediately at best available; remainder cancelled. |
| `Limit` | `GTC` | Rest on book until filled or cancelled. Standard limit order. |
| `Limit` | `IOC` | Fill immediately what's possible at the limit price, cancel the remainder. |
| `Limit` | `FOK` | Fill the entire qty at the limit price or cancel everything. |
| `Limit` | `PostOnly` | Rest on book; if any portion would match immediately, the whole order is cancelled. Earns maker rebates. |

`reduceOnly: true` — order can only reduce an open position, never flip direction. Critical for stop/take-profit safety.

### Position modes

- **One-way mode**: a symbol can have either a long or a short position, not both. The default for retail. Position size is signed.
- **Hedge mode**: a symbol can have a long and a short simultaneously, tracked separately via `positionIdx` (1 = buy side, 2 = sell side). Useful for hedged strategies but adds complexity in position accounting.

`trade-execution-service` assumes one-way mode. Hedge mode would require revising `OrderReconciler.pickMatchingClose` to disambiguate by positionIdx — currently we filter by symbol + time window only.

### Signature

Every authenticated call carries headers:
- `X-BAPI-API-KEY` — the public key
- `X-BAPI-TIMESTAMP` — milliseconds since epoch
- `X-BAPI-RECV-WINDOW` — accepted skew (we use 30,000 = 30s)
- `X-BAPI-SIGN` — HMAC-SHA256 of `timestamp + apiKey + recvWindow + queryStringOrBody`, keyed by the secret

Clock skew is the most common signature failure. `BybitV5RestClient` uses 30s recvWindow specifically because Docker Desktop's WSL VM clock drifts after sleep, producing `retCode=10002` until `w32tm /resync` runs on the host.

## When it works

- **Demo environment is real.** Demo trading on Bybit V5 uses real market data and a parallel orderbook simulator. Strategies that work in demo are far more likely to work in production than backtests-only.
- **Single-symbol focus.** When you're trading one symbol with one position, V5's response shapes are clean and stable.
- **WebSocket-first event delivery.** Order/position/execution events arrive via WS within 50–200ms. REST polling is only useful as a safety net (our `OrderReconciler` runs every 60s exactly for this reason).
- **Closed-PnL endpoint.** `/v5/position/closed-pnl` retrieves up to ~30 days of closed-position records with `avgEntryPrice`, `avgExitPrice`, `closedPnl`, `closedFee` — sufficient for backfill of historical PnL when WS events were missed.

## When it fails

- **Rate-limit IP ban.** A burst of misbehaving polling code triggers a 10-minute ban that affects every IP-sharing pod. Always implement client-side budgeting before relying on Bybit's 403.
- **Clock skew silently corrupts signing.** A pod whose host clock drifts more than `recvWindow` produces uniform signature failures. Surface the timestamp delta in health-check responses.
- **`closedPnl` reuse on rapid re-entry.** When two trades on the same symbol close within seconds, the closed-PnL list orders by `updatedTime` but doesn't always match neatly to our internal `executed_trades` rows. `OrderReconciler.pickMatchingClose` time-windows entries between `openedAt − 1h` and `closedAt + 24h` to avoid mis-attribution.
- **Trail stops on Bybit can be silently cancelled.** Setting `tpslMode` to `Partial` vs `Full` changes how a position-level stop behaves on partial close. Always re-verify the stop state after any action that closes part of a position.
- **Cross-margin vs isolated.** Liquidation prices differ. Cross-margin uses the entire wallet as collateral, isolated uses only the position's allocated initial margin. Misreading this delays liquidation alerts.
- **Demo environment quirks.** Demo orderbook depth is shallower than mainnet. A market order that fills cleanly in demo can produce surprise slippage on mainnet. Always validate execution sizing on mainnet with small qty first.

## What we do today (in projectr-x)

`trade-execution-service` wraps Bybit V5 in two clients:

**`BybitV5RestClient`** — all signed REST endpoints we use:
- `POST /v5/order/create` — place orders (Market for entries currently; Limit + PostOnly is a roadmap item for size > $X)
- `POST /v5/position/trading-stop` — set/update stop-loss and take-profit at position level
- `POST /v5/position/set-leverage` — per-symbol leverage configuration
- `GET /v5/position/list` — current open positions
- `GET /v5/order/realtime` — open orders
- `GET /v5/position/closed-pnl` — closed-trade history for `OrderReconciler` backfill
- `GET /v5/account/wallet-balance` — equity for `DailyPnlCalculator` and the frontend's equity strip

Signature: `BybitV5Signer.sign` — HMAC-SHA256 with the 30,000ms recvWindow. Credentials are AES-GCM encrypted at rest in `exchange_accounts.api_key_ciphertext` / `api_secret_ciphertext`; `CredentialCipher` decrypts lazily per call.

Environment routing: `BybitV5Endpoints.restBaseFor("DEMO" or "MAINNET")` per `exchange_accounts.environment` column. Tests use `bybit.rest-base-override.{env}` to point at WireMock.

**`BybitV5WsClient`** — private stream subscriber:
- Auth: signed `op:auth` first frame
- Subscriptions: `position`, `order`, `execution`, `wallet`
- Reconnect: exponential backoff capped at 30s
- Position event flow: `handlePosition` detects size→0 and calls `OrderReconciler.closeFromReconcile` immediately (v5 fix — previously only set status=CLOSED, leaving PnL/exit_reason NULL until the 60s reconciler ran).

Files:
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java`
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5WsClient.java`
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5Signer.java`
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5Endpoints.java`

Position mode: we assume **one-way mode** everywhere. Setting an account into hedge mode without code changes will produce mis-attributed close events.

Order type policy today: `Market` for entries, position-level `trading-stop` for stops (Bybit-managed, not local). `PostOnly` Limit is on the roadmap for entries above a sizing threshold where 5–10bp of maker rebate vs taker fee meaningfully changes per-trade economics.

## Sources

1. **Bybit V5 API root docs.** https://bybit-exchange.github.io/docs/v5/intro — Index for every endpoint we use.
2. **Rate Limit Rules.** https://bybit-exchange.github.io/docs/v5/rate-limit — 600 req / 5s default, per-endpoint UID buckets, 403 ban behavior.
3. **Place Order endpoint.** https://bybit-exchange.github.io/docs/v5/order/create-order — `orderType`, `timeInForce`, `reduceOnly`, `positionIdx` semantics.
4. **Trading Stop endpoint.** https://bybit-exchange.github.io/docs/v5/position/trading-stop — Position-level SL/TP that we use for stop maintenance instead of conditional orders.
5. **Get Closed PnL.** https://bybit-exchange.github.io/docs/v5/position/close-pnl — Source for `OrderReconciler.closeFromReconcile` backfill.
6. **WebSocket private channel guideline.** https://bybit-exchange.github.io/docs/v5/websocket/private/wallet — Auth flow + topic list including `position`, `order`, `execution`, `wallet`.
7. **Bybit V5 changelog.** https://bybit-exchange.github.io/docs/changelog/v5 — Track endpoint shape changes; v5 is still receiving non-breaking additions monthly.
