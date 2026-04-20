# Trade Execution Service — Design

**Date:** 2026-04-20
**Status:** Approved in brainstorm; implementation plan to follow
**Owner:** Artjoms Stukans

## Goal

Stand up a new backend service that consumes trading signals in real time and mirrors them as real orders on Bybit (V5 API, USDT Perpetual Futures). The service must:

- Subscribe to the existing `crypto:signals` Redis channel — no new signal plumbing.
- Open positions on `STRONG_BUY` / `STRONG_SELL` signals, sized from a risk-percent-of-equity model.
- Maintain stop-loss and take-profit natively on Bybit so positions survive our service crashing.
- Ratchet a trailing stop that mirrors our in-service `OutcomeEvaluator` logic, pushing updates to Bybit's `/v5/position/trading-stop` endpoint.
- Surface per-exchange equity, open positions, and closed-trade history on the existing Portfolio page, including the originating signal "why".
- Treat Bybit as the source of truth for any field touching real money (entry fills, fees, realized P&L, equity).
- Start against Bybit's demo account (1000 USDT paper money) with MAINNET disabled at the API level until Stage 1 acceptance criteria are met.

## Non-goals (explicit)

- Multi-user support. Single operator, single machine, no auth system for the Portfolio UI.
- Changing the existing manual Portfolio tracker behavior.
- Supporting Binance, OKX, or other exchanges in this phase (but `ExchangeClient` interface is designed for later extension).
- Spot trading. This is perpetual-futures-only.
- Mirror of historical signals pre-deployment. Only new signals from the moment auto-trade is enabled.
- Real-money (MAINNET) onboarding flow — gated behind a feature flag, separate promotion decision once DEMO has clean numbers.

## Decisions locked in

| # | Decision | Rationale |
|---|---|---|
| Q1 | **USDT Perpetual Futures** on Bybit V5 | Only path that mirrors both BUY and SELL signals; trailing stops are native; demo account is perp-first. |
| Q2 | **1% risk of equity per trade**, default leverage **3x**, both UI-editable. No notional cap in phase 1. | `qty = (equity × 0.01) / |entry - stop|` matches existing R-multiple math; 3x gives ~5-6 concurrent positions of margin headroom at $1000 equity. |
| Q3 | **Close-only on strong opposite signal**, no auto-flip. Requires **2-tick persistence** before acting. | Hysteresis filters 1-tick noise; not auto-opening opposite avoids double-commit on a contrarian blip. |
| Q4 | **All six guardrails enabled** with defaults: max 5 concurrent, -5% daily loss halt, 60s signal age, 24h position max age, kill switch, 2-tick flip persistence. | Pure capital-preservation; signal-service already handles quality filtering. |
| Q5 | **Stacked layout** on Portfolio page: Manual section on top (unchanged), Bybit card below. Future exchanges stack underneath. | Live real-money P&L deserves permanent screen space, not a tab click. |
| Q6 | **Inline detector badge + alignment + regime** in positions table; click row → modal with full signal breakdown. Reuses existing `AiAnalysisModal` scaffold. | Scannable table; full context one click away; matches existing UI pattern. |

**Additional decisions made during design:**

- **Approach 1 — single new service, pluggable `ExchangeClient` interface.** One new Quarkus service (`services/trade-execution-service/`) plus a new pure-Java Maven module (`shared-trade-core`) holding `TrailCalculator` extracted from `OutcomeEvaluator`. Both services depend on the shared module so trail math can't drift.
- **Direct REST + WebSocket client to Bybit**, no third-party SDK. Our endpoint surface is small (~8 endpoints, 4 WS topics); an SDK would abstract things we'd re-abstract anyway.
- **Bybit as source of truth for real-money fields** — we store our decisions (entry price *target*, stop, target, signal id, detector) and Bybit's responses (entry *fill*, qty, leverage, realized P&L, fees, equity). UI always shows Bybit's numbers for anything denominated in USDT.

## Component topology

```
services/
└── trade-execution-service/              NEW — Quarkus 3.17 / Java 21
    ├── Dockerfile                        multistage, mirrors analytics-service
    ├── pom.xml                           depends on shared-trade-core
    └── src/main/java/com/cryptoradar/execution/
        ├── client/
        │   ├── ExchangeClient.java       interface (place, cancel, leverage, trading-stop, list)
        │   └── bybit/
        │       ├── BybitV5RestClient.java    Quarkus REST Client Reactive
        │       ├── BybitV5WsClient.java      private streams: position, execution, order, wallet
        │       ├── BybitV5Signer.java        HMAC-SHA256 request signing
        │       └── BybitV5Endpoints.java     URL constants per environment
        ├── intake/
        │   ├── SignalSubscriber.java     @Observes on Redis 'crypto:signals'
        │   └── FlipTracker.java          2-tick persistence state per symbol
        ├── policy/
        │   └── GuardrailPolicy.java      six rules; returns ACCEPT or BLOCK(reason)
        ├── lifecycle/
        │   ├── OrderPlacer.java
        │   ├── TrailMirror.java          @Scheduled(every="60s")
        │   └── OrderReconciler.java      StartupEvent + @Scheduled(every="60s")
        ├── security/
        │   ├── CredentialCipher.java     AES-GCM/256 with rotating master key
        │   └── PermissionValidator.java  /v5/user/query-api, rejects if withdraw=1
        ├── model/                        @Entity classes
        ├── repository/                   Panache repos
        ├── resource/                     REST: /api/execution/*
        └── ws/                           /ws/execution broadcast to frontend

shared-trade-core/                        NEW — plain Maven jar, no Quarkus
└── src/main/java/com/cryptoradar/core/
    ├── TrailCalculator.java              extracted from OutcomeEvaluator
    ├── TrailConfig.java                  moved from signal-service model/
    └── RUnitMath.java                    qty/risk helpers
```

- `signal-service/pom.xml` adds `<dependency>shared-trade-core</dependency>`. `OutcomeEvaluator.updateTrailingStop` refactored to delegate to `TrailCalculator` — behavior-preserving change, covered by existing `OutcomeEvaluatorTrailingTest`.
- `docker-compose.yml` gets a `trade-execution-service` entry matching the analytics-service template.
- Host port `31087`, internal port `8087`. (Next in our 31xxx range per `~/.claude/rules/local-port-ranges.md`.)
- `api-gateway` adds `ProxyResource.forwardExecution(...)` forwarding to `http://trade-execution-service:8087`.

## Data model

Three new tables on the `marketdata` TimescaleDB instance (same DB as `signal_outcomes` — joins for "why" are local). None are hypertables — low volume, point-lookup queries dominate.

### `exchange_accounts`

One row per exchange/environment pair. Holds encrypted credentials and per-account settings.

```sql
CREATE TABLE exchange_accounts (
  id                          BIGSERIAL PRIMARY KEY,
  exchange                    VARCHAR(32) NOT NULL,         -- 'BYBIT'
  environment                 VARCHAR(16) NOT NULL,         -- 'DEMO' | 'MAINNET'
  api_key_encrypted           TEXT NOT NULL,                -- AES-GCM ciphertext, IV prepended, base64
  api_secret_encrypted        TEXT NOT NULL,
  label                       VARCHAR(64),
  auto_trade_enabled          BOOLEAN NOT NULL DEFAULT false,
  kill_switch                 BOOLEAN NOT NULL DEFAULT false,
  risk_percent                NUMERIC(5,2) NOT NULL DEFAULT 1.0,
  default_leverage            INT NOT NULL DEFAULT 3,
  max_concurrent_positions    INT NOT NULL DEFAULT 5,
  max_daily_loss_percent      NUMERIC(5,2) NOT NULL DEFAULT 5.0,
  signal_age_seconds          INT NOT NULL DEFAULT 60,
  position_max_age_hours      INT NOT NULL DEFAULT 24,
  flip_persistence_ticks      INT NOT NULL DEFAULT 2,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (exchange, environment)
);
```

### `executed_trades`

One row per real position. Links back to `signal_outcomes.signal_id` for the "why" join.

```sql
CREATE TABLE executed_trades (
  id                       BIGSERIAL PRIMARY KEY,
  exchange_account_id      BIGINT NOT NULL REFERENCES exchange_accounts(id),
  signal_id                VARCHAR(64),                  -- NULL allowed for orphan-from-reconcile
  symbol                   VARCHAR(32) NOT NULL,
  direction                VARCHAR(8)  NOT NULL,         -- 'LONG' | 'SHORT'
  strategy                 VARCHAR(64),
  exchange_order_id        VARCHAR(64),                  -- Bybit orderId of the opening order
  exchange_order_link_id   VARCHAR(64),                  -- our idempotency key
  exchange_position_idx    INT,                          -- one-way mode: 0
  status                   VARCHAR(24) NOT NULL,         -- PENDING_PLACE|OPEN|CLOSING|CLOSED|FAILED|CANCELLED
  entry_price              NUMERIC(20,8),                -- Bybit: avg fill from execution list
  qty                      NUMERIC(20,8),                -- Bybit: position size
  leverage                 INT,                          -- Bybit: effective leverage at open
  stop_price               NUMERIC(20,8),                -- OURS: initial SL from signal
  target_price             NUMERIC(20,8),                -- OURS: TP from signal
  dynamic_stop_price       NUMERIC(20,8),                -- OURS: current trail stop
  trail_highest_r          NUMERIC(10,4) DEFAULT 0,      -- OURS: highest trail rung achieved
  trail_triggered_at       TIMESTAMPTZ,                  -- first time trail moved off initial
  exit_price               NUMERIC(20,8),                -- Bybit: closing fill
  exit_reason              VARCHAR(24),                  -- TARGET|TRAIL_STOP|INITIAL_STOP|EXPIRED|FLIP_CLOSE|MANUAL|KILL
  realized_pnl_usdt        NUMERIC(20,8),                -- Bybit: /v5/position/closed-pnl
  realized_r_multiple      NUMERIC(10,4),                -- DERIVED: real pnl vs stop-risk
  fees_usdt                NUMERIC(20,8),                -- Bybit: sum of open + close execFee
  opened_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  closed_at                TIMESTAMPTZ,
  last_sync_at             TIMESTAMPTZ,                  -- most recent reconcile against Bybit
  created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_executed_trades_signal          ON executed_trades(signal_id);
CREATE INDEX idx_executed_trades_status          ON executed_trades(status, opened_at DESC);
CREATE INDEX idx_executed_trades_account_symbol  ON executed_trades(exchange_account_id, symbol, status);
```

**Field source labels** are enforced in code: fields labelled `Bybit:` are only written by `BybitV5WsClient` or `OrderReconciler` consuming Bybit REST responses. Fields labelled `OURS:` are only written by `OrderPlacer` / `TrailMirror`. Mixing these in code is a review-time red flag.

### `execution_events`

Append-only audit log. Every decision and state transition.

```sql
CREATE TABLE execution_events (
  id                    BIGSERIAL PRIMARY KEY,
  exchange_account_id   BIGINT NOT NULL REFERENCES exchange_accounts(id),
  event_type            VARCHAR(48) NOT NULL,
  signal_id             VARCHAR(64),
  executed_trade_id     BIGINT REFERENCES executed_trades(id),
  metadata              JSONB,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_execution_events_account ON execution_events(exchange_account_id, created_at DESC);
```

`event_type` values — closed enum enforced by `ExecutionEventType`:
```
SIGNAL_ACCEPTED, SIGNAL_BLOCKED_KILL_SWITCH, SIGNAL_BLOCKED_AUTO_TRADE_OFF,
SIGNAL_BLOCKED_MAX_CONCURRENT, SIGNAL_BLOCKED_DAILY_HALT, SIGNAL_BLOCKED_DEDUP,
SIGNAL_BLOCKED_SIGNAL_AGE, SIGNAL_BLOCKED_PERSISTENCE,
ORDER_PLACED, ORDER_FILLED, ORDER_REJECTED, ORDER_CANCELLED,
TRAIL_UPDATED, POSITION_CLOSED,
KILL_SWITCH_TOGGLED, AUTO_TRADE_TOGGLED,
DAILY_HALT_ENTERED, DAILY_HALT_EXITED,
RECONCILE_ORPHAN_DETECTED, RECONCILE_CLOSED_EXTERNALLY, RECONCILE_DRIFT_DETECTED,
AUTH_FAILURE, BYBIT_CIRCUIT_OPEN, WS_DISCONNECTED, WS_RECONNECTED,
FLIP_CLOSE_TRIGGERED
```

**Schema DDL lives in `db/init/execution-init.sql`** (new file), idempotent (`CREATE TABLE IF NOT EXISTS` + `DO $$ ... IF NOT EXISTS $$` blocks), also mirrored as `devops/overlays/dev/execution-db-init-sql.yaml` ConfigMap.

## Signal-to-order lifecycle

### Source-of-truth boundary

| Field | Source | Written by |
|---|---|---|
| `symbol, direction, signal_id, strategy` | Ours | `OrderPlacer` on signal acceptance |
| `target_price, stop_price, dynamic_stop_price, trail_highest_r` | Ours | `OrderPlacer`, `TrailMirror` |
| `entry_price, qty, leverage` | Bybit | `BybitV5WsClient` (execution stream) + `OrderReconciler` |
| `exit_price, realized_pnl_usdt, fees_usdt` | Bybit | `OrderReconciler` via `/v5/position/closed-pnl` |
| `realized_r_multiple` | Derived | `OrderReconciler` on close. LONG: `(exit - entry) / (entry - stop)`. SHORT: `(entry - exit) / (stop - entry)`. Always uses Bybit's real `entry_price`, not the signal's intended entry. |
| Account equity, available margin, open P&L, today realized | Bybit | `WalletSync` via WS `wallet` topic |

### Intake

`SignalSubscriber` subscribes to Redis `crypto:signals` channel (existing, published by `RedisEventPublisher` in signal-service). Consumes **both** `alert` and `overview` envelope types — alerts for low-latency entry triggers on transitions, overviews for FlipTracker tick recording across all symbols.

```
onMessage({type, data}):
  candidates = []
  if type == "alert":
      candidates = [ data.signal ]                                       // single TradingSignal
  elif type == "overview":
      candidates = data.signals.filter(s -> s.signal in {STRONG_BUY, STRONG_SELL})

  for each candidate:
      flipResult = FlipTracker.observe(symbol, candidate.signal)          // records tick, returns action
      // flipResult ∈ { NO_ACTION, ENTER_LONG, ENTER_SHORT, CLOSE_LONG, CLOSE_SHORT }
      //   NO_ACTION      → not actionable or persistence not yet reached
      //   ENTER_LONG/SHORT → 2 consecutive same-direction strong ticks with no existing opposite position
      //   CLOSE_LONG/SHORT → 2 consecutive strong opposite ticks against an existing position

      switch flipResult:
          NO_ACTION:     skip
          ENTER_*:       decision = GuardrailPolicy.evaluate(account, candidate)
                         if ACCEPT → OrderPlacer.place(candidate, account)
                         if BLOCK  → log SIGNAL_BLOCKED_<reason>
          CLOSE_LONG:    OrderPlacer.close(symbol=symbol, direction=LONG, reason=FLIP_CLOSE)
          CLOSE_SHORT:   OrderPlacer.close(symbol=symbol, direction=SHORT, reason=FLIP_CLOSE)
```

`FlipTracker` state is kept in-memory per symbol (consecutive-same-direction counter); rebuilt on startup from the last two minutes of received signals (no persistence needed — worst case is a missed flip during the first ~60s after restart).

### GuardrailPolicy

Evaluated in this order, short-circuits on first block:

```
1. account.kill_switch            → SIGNAL_BLOCKED_KILL_SWITCH
2. !account.auto_trade_enabled    → SIGNAL_BLOCKED_AUTO_TRADE_OFF
3. signalAgeSec > max             → SIGNAL_BLOCKED_SIGNAL_AGE
4. openPositions >= max_concurrent_positions → SIGNAL_BLOCKED_MAX_CONCURRENT
5. todayPnlPct < -max_daily_loss_percent     → SIGNAL_BLOCKED_DAILY_HALT
6. existing OPEN row on (symbol, direction, strategy) → SIGNAL_BLOCKED_DEDUP
```

All thresholds read fresh from `exchange_accounts` per call — settings changes take effect immediately.

### Order placement

`OrderPlacer.place(candidate, account)` — one row inserted, one-to-three Bybit calls:

1. `POST /v5/position/set-leverage` with `leverage = account.default_leverage`. Idempotent — Bybit returns `retCode=110043` if unchanged; we treat that as success.
2. Compute `qty = RUnitMath.computeQty(equity, account.risk_percent, entry, stop, lotSize)` (floors to Bybit's `lotSizeFilter.qtyStep`).
3. Insert `executed_trades` row with `status=PENDING_PLACE`, generate `exchange_order_link_id = "ex-{id}"` (stable for the row's lifetime — any retry reuses it so Bybit dedupes via `retCode=110061`).
4. `POST /v5/order/create` with:
    - `category=linear`
    - `symbol`, `side` (Buy/Sell), `orderType=Market`, `qty`
    - `takeProfit=target_price`, `stopLoss=stop_price`
    - `tpslMode=Full`, `tpOrderType=Market`, `slOrderType=Market`
    - `orderLinkId` = our idempotency key
5. On response:
    - `retCode=0` → update row `exchange_order_id`, wait for WS `execution` fill event.
    - `retCode=110007` (insufficient margin) → mark row `FAILED`, emit event, return. Next tick may succeed.
    - `retCode=110061` (duplicate orderLinkId) → treat as success if a previous attempt timed out; look up the existing order.
    - Other errors → `FAILED`, emit `ORDER_REJECTED`.
6. WS `execution` arrives → update row `entry_price` (avg from fills), `qty` (Bybit's actual), `leverage`, set `status=OPEN`, emit `ORDER_FILLED`.

Partial fills: if filled qty < requested after 30s, cancel remaining and proceed with whatever filled. Empty fill (0 qty) → mark `CANCELLED`.

### Trail mirroring

`TrailMirror` runs `@Scheduled(every="60s")`. For each `status=OPEN` row:

```
currentPrice = marketDataService.getLastPrice(symbol)     // existing service
isLong       = direction == "LONG"
risk         = |entry_price - stop_price|
riskPct      = risk / entry_price * 100
mfeRCurrent  = TrailCalculator.computeMfeR(entry, currentPrice, risk, isLong)

// Also consider cumulative MFE from Bybit position (they track high-water-mark via unrealisedPnl history)
//   But simpler: we track via our 60s ticks; Bybit's TP/SL triggers are the safety net

newTrailR = TrailCalculator.computeNewRung(
    mfeR: mfeRCurrent,
    activationR: TrailConfig.DEFAULT.activationR(),
    stepR: TrailConfig.DEFAULT.stepR(),
    offsetR: TrailConfig.DEFAULT.offsetR(),
    currentHighestR: row.trail_highest_r
)

if (newTrailR > row.trail_highest_r) {
    newStop = isLong ? entry + newTrailR*risk : entry - newTrailR*risk
    POST /v5/position/trading-stop { symbol, stopLoss: newStop, tpslMode: Full }
    if retCode=0:
        row.trail_highest_r = newTrailR
        row.dynamic_stop_price = newStop
        row.trail_triggered_at ??= now()
        emit TRAIL_UPDATED event
}
```

Default trail config (from Section 1 of `CLAUDE.md`): `activationR=1.0, stepR=0.5, offsetR=0.5`. Extracted into `shared-trade-core.TrailCalculator` so this logic is identical to `OutcomeEvaluator`'s — single source of truth.

### Position close paths

**Six** real close paths, all funneled through `OrderReconciler` for the final DB write. **Kill switch is NOT a close path** — it only blocks new entries; the row below is included for contrast.

| Path | Trigger | Exit reason | Notes |
|---|---|---|---|
| Target hit | Bybit internal TP trigger | `TARGET` | WS `execution` with `stopOrderType=TakeProfit`. |
| Initial stop hit | Bybit internal SL trigger, before trail activated | `INITIAL_STOP` | Distinguished by `dynamic_stop_price == stop_price` at close. |
| Trail stop hit | Bybit internal SL trigger, after trail activated | `TRAIL_STOP` | `dynamic_stop_price != stop_price`. |
| Flip close | FlipTracker `CLOSE_LONG`/`CLOSE_SHORT` | `FLIP_CLOSE` | Market reduceOnly order from our service. |
| Position age expiry | `TrailMirror` finds `now() - opened_at > 24h` | `EXPIRED` | Market reduceOnly close. |
| Manual close | UI button → `POST /trades/{id}/close` | `MANUAL` | Same path as flip close. |
| Kill switch | **Does NOT close** — only blocks new entries. | — | Explicit liquidation is a separate UI action (`POST /accounts/{id}/close-all` with `confirm: "CLOSE_ALL"`). |

On any close: Bybit emits `position` WS event with `size=0`. `OrderReconciler` fetches `/v5/position/closed-pnl?symbol=X&limit=10`, finds matching record by opening timestamp and `orderLinkId` prefix, fills in `exit_price, realized_pnl_usdt, fees_usdt`, computes `realized_r_multiple`, sets `status=CLOSED` + `exit_reason`, emits `POSITION_CLOSED`.

### Reconciliation

Runs on `StartupEvent` and `@Scheduled(every="60s")`:

```
for each exchange_account:
    bybit_open  = GET /v5/position/list?category=linear&settleCoin=USDT  (size>0 only)
    local_open  = SELECT * FROM executed_trades WHERE exchange_account_id=? AND status IN ('OPEN','PENDING_PLACE','CLOSING')

    for each bybit_open not matched in local_open:
        INSERT orphan row with signal_id=NULL, emit RECONCILE_ORPHAN_DETECTED
        (position stays managed by Bybit TP/SL — we just start tracking it)

    for each local_open not matched in bybit_open:
        fetch /v5/position/closed-pnl?symbol=X, match by orderLinkId or opening time ± 5s
        UPDATE row: status=CLOSED, exit_price, exit_reason (from Bybit closing order's stopOrderType),
                    realized_pnl_usdt, fees_usdt, closed_at
        emit RECONCILE_CLOSED_EXTERNALLY

    UPDATE last_sync_at on survivors
```

Restart-safe: Bybit is truth for existence, we're truth for "why". Our service can crash, restart, and immediately pick up where it was.

## Security

### Credential storage

- **Master key** from env var `EXECUTION_MASTER_KEY` (base64-encoded 256-bit AES key). Lives in `.env` (gitignored) for local dev; in k3s it's a `Secret` mounted as env. Never in git, never in Dockerfile, never in manifests.
- **Encryption**: AES-GCM with 256-bit key, random 96-bit IV per ciphertext, IV prepended to ciphertext, base64-encoded into `TEXT` column.
- **Decryption**: happens inside `BybitV5Signer` just before HMAC-signing. Plaintext held in a local `byte[]`, zeroed with `Arrays.fill(..., (byte) 0)` after use. Never assigned to a field. Never logged. Never in an exception message.
- **Rotation**: `EXECUTION_MASTER_KEY_PREV` env var optionally set during rotation. `CredentialCipher.decrypt` tries current key first, falls back to prev on auth failure. One-off `RotateCredentialsJob` re-encrypts all rows under the new key, then prev env var removed.

### Bybit request signing

Per V5 spec:

```
timestamp = now().toEpochMilli()
payload   = timestamp + apiKey + recvWindow + (queryString for GET, body for POST)
sign      = HMAC-SHA256(apiSecret, payload).toHex()

Headers:
  X-BAPI-API-KEY: <apiKey>
  X-BAPI-TIMESTAMP: <timestamp>
  X-BAPI-RECV-WINDOW: 5000
  X-BAPI-SIGN: <sign>
```

Wrapped in a Quarkus `ClientRequestFilter` so individual repos never touch the secret.

### Permission validation

On `POST /accounts` (adding or replacing a key):

1. Decrypt, call `GET /v5/user/query-api`.
2. Validate the returned `permissions` object:
    - Must grant order placement + position management on derivatives (field names per current Bybit V5 spec at implementation time — verified against `/v5/user/query-api` response shape in `PermissionValidatorIntegrationTest` with a real captured response).
    - **Must NOT grant any withdrawal permission.** Hard guardrail: the key physically cannot drain funds.
    - Must grant wallet read so we can fetch balances.
3. If any check fails: return HTTP 400 with a specific message; row is never inserted.

### Kill switch

- Single boolean column on `exchange_accounts`. Flipped via `POST /accounts/{id}/kill-switch {enabled}`.
- `GuardrailPolicy` reads fresh per-call — no caching.
- Blocks new entries only. Existing positions keep Bybit-side SL/TP (durable without our service).
- Explicit liquidation is a separate endpoint (`POST /accounts/{id}/close-all`) requiring a confirmation token.

### Demo/live gate

- `environment` column `DEMO` | `MAINNET`. Write-once — changing requires delete + re-add.
- Base URLs selected per environment:
    - `DEMO` → `https://api-demo.bybit.com`
    - `MAINNET` → `https://api.bybit.com`
- **Feature flag `execution.mainnet.enabled=false`** (default). When false, `POST /accounts` with `environment=MAINNET` returns HTTP 400. Only flipped after Stage 0 acceptance.
- UI: DEMO = neutral grey badge. MAINNET = red border, "LIVE MONEY" warning on every destructive action, first-save modal requires typing `I UNDERSTAND`.

### Never-log list

- API key plaintext
- API secret plaintext
- `X-BAPI-SIGN` values
- Full request/response bodies for signed endpoints (only status + path + symbol in structured logs)

## Frontend contract

### REST endpoints (under `/api/execution`, proxied through api-gateway)

```
GET    /accounts                             list all configured accounts
POST   /accounts                             create account; validates permissions, encrypts, inserts
PATCH  /accounts/{id}                        update settings (risk %, leverage, guardrails, auto_trade)
DELETE /accounts/{id}                        delete; rejected if any OPEN positions

GET    /accounts/{id}/wallet                 live Bybit: equity, available, openPnl, todayRealized
GET    /accounts/{id}/positions              open positions with joined signal "why"
GET    /accounts/{id}/trades?limit=50        closed trades
GET    /accounts/{id}/events?limit=100       audit log slice
GET    /accounts/{id}/trades/{tradeId}/why   full signal breakdown (dim scores, regime, detector, AI)

POST   /accounts/{id}/kill-switch            {enabled: bool}
POST   /accounts/{id}/close-all              {confirm: "CLOSE_ALL"} — liquidates all OPEN
POST   /accounts/{id}/trades/{tradeId}/close manual close one position
```

### WebSocket — `/ws/execution`

Client subscribes with an optional `?accountId=N` filter. Envelope matches existing pattern:
```json
{ "type": "wallet" | "position" | "trade" | "event", "accountId": 1, "data": {...} }
```

- `wallet` — 500ms-debounced wallet snapshots (equity, available, openPnl, todayRealized).
- `position` — opens, updates (trail moves), closes. Shape = `executed_trades` row.
- `trade` — lifecycle transitions (placed, filled, closed) — drives toast/notification UI.
- `event` — audit entries (kill switch toggles, guardrail blocks, halts) — drives the audit tail.

### Frontend components

Under `frontend/src/components/portfolio/`:

```
PortfolioPage                     existing, extended to render ExchangeAccountsSection below Manual
└── ExchangeAccountsSection       NEW
    ├── ExchangeCard (per account)
    │   ├── ExchangeCardHeader    name, connection badge, auto-trade toggle, kill switch, ⚙ gear
    │   ├── EquitySummary         5 cards: equity, available, openPnl, todayRealized, positionsOpen
    │   ├── OpenPositionsTable    columns: symbol, side, entry, current, stop, target, P&L, why
    │   │   └── WhyModal          reuses AiAnalysisModal scaffold; shows 6-dim scores, regime,
    │   │                         detector reason, fills, trail state, AI text
    │   └── RecentTradesList      last 24h closed, exit-reason badge
    └── AddExchangeButton
        └── ExchangeSetupModal    API key/secret form; DEMO/MAINNET toggle; MAINNET gated
```

Settings opens via the ⚙ gear icon as a side-panel (not a modal) so the positions list stays visible:
```
Risk per trade        [ 1.0 ] %
Default leverage      [ 3   ] x
Max concurrent        [ 5   ]
Daily loss halt       [ 5.0 ] %
Signal max age        [ 60  ] s
Position max age      [ 24  ] h
Flip persistence      [ 2   ] ticks
[ Save ]  [ Cancel ]
```

### TypeScript types (added to `frontend/src/types/index.ts`)

```typescript
interface ExchangeAccount {
    id: number;
    exchange: string;
    environment: 'DEMO' | 'MAINNET';
    label: string | null;
    autoTradeEnabled: boolean;
    killSwitch: boolean;
    riskPercent: number;
    defaultLeverage: number;
    maxConcurrentPositions: number;
    maxDailyLossPercent: number;
    signalAgeSeconds: number;
    positionMaxAgeHours: number;
    flipPersistenceTicks: number;
}

interface ExecutedTrade {
    id: number;
    accountId: number;
    signalId: string | null;
    symbol: string;
    direction: 'LONG' | 'SHORT';
    strategy: string | null;
    status: 'PENDING_PLACE' | 'OPEN' | 'CLOSING' | 'CLOSED' | 'FAILED' | 'CANCELLED';
    entryPrice: number | null;
    qty: number | null;
    leverage: number | null;
    stopPrice: number;
    targetPrice: number;
    dynamicStopPrice: number | null;
    trailHighestR: number;
    trailTriggeredAt: string | null;
    exitPrice: number | null;
    exitReason: string | null;
    realizedPnlUsdt: number | null;
    realizedRMultiple: number | null;
    feesUsdt: number | null;
    openedAt: string;
    closedAt: string | null;
}

interface WalletSnapshot {
    equity: number;
    available: number;
    openPnl: number;
    todayRealized: number;
    positionsOpen: number;
}

interface ExecutionEvent {
    id: number;
    eventType: string;
    signalId: string | null;
    executedTradeId: number | null;
    metadata: Record<string, unknown>;
    createdAt: string;
}
```

### `lib/api.ts` — typed wrappers

`listAccounts`, `createAccount`, `updateAccountSettings`, `deleteAccount`, `getWallet`, `getPositions`, `getTrades`, `getTradeWhy`, `getEvents`, `toggleKillSwitch`, `closeAll`, `closeTrade`. WebSocket helper `useExecutionStream(accountId)` follows the existing `useSignalStream` pattern.

### api-gateway proxy

`ProxyResource.forwardExecution(@PathParam("path") String path, ...)` routes any `/api/execution/**` to `http://trade-execution-service:8087/api/execution/**`. WS proxy follows same pattern as `/ws/signals`.

## Error handling + observability

### Bybit API failure responses

| Failure | Detection | Response |
|---|---|---|
| HTTP 429 | `Retry-After` header or `retCode=10006` | Exponential backoff (1s→2s→4s, max 16s), 3 retries, then fail; guardrail blocks further attempts on same symbol for 30s |
| HTTP 5xx / timeout | OkHttp exception or status≥500 | GETs: retry 2× with 500ms backoff. POSTs: retry ONLY if no response body (network drop before Bybit received). With response → do not retry |
| `retCode=10003` invalid signature | any response | Disable account: `kill_switch=true`, emit `AUTH_FAILURE`, surface alert to UI |
| `retCode=110007` insufficient margin | order response | `SIGNAL_BLOCKED_INSUFFICIENT_MARGIN`, skip signal, no retry |
| `retCode=110061` duplicate orderLinkId | order response | Treat as success; look up the existing order to populate row |
| Partial fill after 30s | poll | Cancel remainder, store avg fill as entry |
| Reconciliation divergence | 60s drift scan | Insert orphan row OR close local row, event emitted per class |

### Idempotency

- Order placement: `orderLinkId = "ex-{id}-{retryAttempt}"`. Retrying a timed-out placement with same linkId is safe — Bybit rejects duplicates with 110061.
- Trail updates: `POST /v5/position/trading-stop` with same `stopLoss` value is idempotent per Bybit spec.

### WebSocket resilience

- Auto-reconnect on disconnect, jittered backoff (base 1s, cap 30s).
- On reconnect: re-auth with signed message, re-subscribe to `position`, `execution`, `order`, `wallet` topics, fire full `/v5/position/list` + `/v5/position/closed-pnl` catch-up sweep.
- Health degrades to `WARN` (not `DOWN`) if private WS disconnected >60s — REST fallback still functioning.

### Circuit breaker (per account)

5 consecutive failed Bybit calls within 60s → open circuit for 30s → reject new orders during open window (logged `BYBIT_CIRCUIT_OPEN`). Reconciler reads unaffected. Implemented via Quarkus SmallRye Fault Tolerance `@CircuitBreaker`.

### Structured logging (per `~/.claude/rules/observability-and-logging.md`)

- JSON logs via `quarkus-logging-json`.
- MDC per call: `service=trade-execution`, `accountId`, `exchange=BYBIT`, `environment=DEMO|MAINNET`, and when relevant: `symbol`, `signalId`, `tradeId`, `orderLinkId`.
- Never logged: api_key/secret plaintext, `X-BAPI-SIGN`, full signed request/response bodies.
- `INFO` events only at lifecycle transitions. No per-loop-iteration logging.
- `ERROR` includes exception as second argument, never `e.getMessage()` string-concat.

### Metrics (Micrometer, Prometheus-scrapeable)

```
execution_signals_received_total{outcome="accepted"|"blocked", reason=?}  counter
execution_orders_placed_total{symbol, direction}                          counter
execution_orders_failed_total{retCode}                                    counter
execution_bybit_latency_seconds{endpoint}                                 histogram
execution_positions_open                                                  gauge (per account)
execution_daily_realized_usdt                                             gauge (per account)
```

Cardinality discipline: `symbol` has ~13 values (known bounded set), `retCode` is a Bybit-defined enum, `endpoint` is our call-site static. No unbounded tags.

### Tracing

OpenTelemetry wired via existing project pattern. Span names: `execution.handleSignal`, `execution.placeOrder`, `execution.updateTrailingStop`, `execution.reconcile`. Dynamic values (symbol, id) go in span attributes, never in span name.

### Health endpoints

- `/q/health/liveness` — process responsive (always 200 unless JVM wedged).
- `/q/health/readiness` — 200 only if: DB reachable AND Redis reachable AND at least one account's private WS is currently connected OR was connected within last 90s.
- `/q/health/ready-bybit-{accountId}` — per-account deep check pings `/v5/market/time` (unauthed, no rate-limit impact).

### Audit trail

`execution_events` is the ground truth for decisions. Queryable by timestamp + accountId. UI audit tail via `GET /events?limit=100`.

## Testing + rollout

### Test pyramid

**Unit (pure logic):**
- `GuardrailPolicyTest` — all six rules, table-driven.
- `FlipTrackerTest` — 1-tick, 2-tick persistence, neutral breaks streak, opposite strong confirms.
- `TrailMirrorTest` — MFE sequence → correct rung levels, idempotent re-call, direction-symmetric.
- `RUnitMathTest` — qty rounds down to Bybit `lotSizeFilter.qtyStep`, risk-$ from equity × pct, zero-risk edge case.
- `BybitV5SignerTest` — signature matches Bybit's published test vector.
- `CredentialCipherTest` — encrypt/decrypt round-trip, IV uniqueness across repeated encrypt calls, dual-decrypt during rotation.
- `FlipClosePolicyTest` — close-only on strong, no auto-flip.

**Integration (Bybit stubbed via WireMock):**
- `OrderPlacerIntegrationTest` — signal in → order placed with expected payload → row inserted with Bybit-sourced entry.
- `ReconcilerIntegrationTest` — local vs Bybit divergence → correct diff, DB fixup, event emitted.
- `WsRecoveryIntegrationTest` — WS disconnect + reconnect triggers catch-up, no duplicate events.
- `PermissionValidatorIntegrationTest` — rejects keys with `Withdraw` permission.

**No live Bybit-demo tests in CI.** Live validation is manual smoke test (below).

**Coverage targets (not gated, aimed at):**
- `GuardrailPolicy`, `FlipTracker`, `TrailMirror`, `RUnitMath`: 100% branch.
- `BybitV5RestClient`, `BybitV5WsClient`: reconnect + signer branches.
- `OrderReconciler`: drift scenarios.
- Encryption boundary: round-trip + weak-permission rejection.

### Rollout plan

**Stage 0 — DEMO only**

- `execution.mainnet.enabled=false` feature flag locked on. `POST /accounts` rejects `environment=MAINNET` with HTTP 400.
- User adds one Bybit DEMO account, 1000 USDT paper balance.
- `auto_trade_enabled=false` by default — user manually enables after reviewing their first few signals in the UI.
- Kill switch armed by default — user disarms to start.
- Run minimum **7 days + 10 closed trades**.

**Stage 0 acceptance criteria** (all must pass before Stage 1):

- Zero `AUTH_FAILURE` events.
- Zero orphan positions at any 60s reconciliation tick.
- Realized P&L in `executed_trades` matches `/v5/position/closed-pnl` to the cent for every CLOSED row.
- Zero `RECONCILE_DRIFT_DETECTED` events outside WS reconnect windows.
- Trail ladder math matches post-hoc replay against the same 1m candle data for every trade that triggered trail.
- No `ORDER_REJECTED` events caused by our malformed payload (110007 insufficient-margin is acceptable; signature/format errors are not).

**Stage 1 — MAINNET gate**

- Flip `execution.mainnet.enabled=true` in config.
- User adds a MAINNET account with small capital (decision out of scope of this service).
- UI enforces MAINNET red-border treatment + `I UNDERSTAND` modal on first save.

### Manual smoke test protocol (pre-deploy checklist)

1. Add DEMO account. Verify: connection badge turns green within 5s, wallet WS streams initial equity reading.
2. Wait for real `STRONG_BUY` OR inject via dev-only `POST /api/execution/test/inject-signal` (behind `execution.dev-mode.enabled=true`, never enabled in production).
3. Verify: order placed with correct payload, entry/stop/target match signal, row inserted, UI updates <1s after fill.
4. Wait for trail activation OR simulate via dev-mode price push.
5. Verify: Bybit SL patched via `/v5/position/trading-stop`, `dynamic_stop_price` matches.
6. Close position via UI "Close" button. Verify: reduce-only order sent, reconciler picks up close, `realized_pnl_usdt` populated from `closed-pnl`, row `status=CLOSED`, `exit_reason=MANUAL`.
7. Toggle kill switch. Verify new signal → `SIGNAL_BLOCKED_KILL_SWITCH` event, existing positions unaffected.
8. Revoke API key in Bybit UI. Verify: next call → `AUTH_FAILURE` event, account auto-killed, UI surfaces red alert.

## Out-of-scope / future extension points

- **Multi-exchange**: `ExchangeClient` interface exists; Binance Futures client can be added as a new implementation without touching guardrails, DB schema, or frontend beyond a new card entry in `ExchangeAccountsSection`.
- **Per-strategy trail config**: `TrailMirror` uses `TrailConfig.DEFAULT` today. Hook exists to read per-strategy config from `signal_outcomes.trail_*` columns in a future iteration — same as `OutcomeEvaluator` already supports.
- **Position scaling** (add to winners): not in scope; signal engine doesn't emit "add" signals today.
- **Cross-margin vs isolated-margin**: defaults to whatever the account is set to in Bybit UI; not configurable from our settings panel in phase 1.
- **Alerts / notifications** (Telegram, email): out of scope. UI toast + events log is the notification surface for phase 1.

## Known drift risks

- **Bybit V5 API version bumps**: V5 is current and stable as of 2026-04. If Bybit cuts a V6 we'd need to re-test every endpoint. Mitigation: keep `BybitV5Endpoints.java` as a single choke point, version the namespace in package name.
- **Trail math drift between `OutcomeEvaluator` and `TrailMirror`**: mitigated by sharing `TrailCalculator` via `shared-trade-core` module. Any change to trail math requires editing one file.
- **Fee schedule changes**: we read fees from Bybit, so schedule changes propagate automatically — but our `MIN_RR=2.0` floor in signal-service assumes fees around 10 bps round-trip. If Bybit fee schedule changes materially, re-tune that floor.

---

## Frontend visual decisions (locked 2026-04-21)

Consolidated from the visual-companion brainstorming sessions. Stored inline so Plan 3 implementers have an unambiguous target.

### Layout

- **Stacked sections** on Portfolio page. Manual card stays at top (backward compat), `ExchangeAccountsSection` sits below, rendering one card per exchange account. Future exchanges append to the stack.
- **Empty state**: dashed-border card mirroring the exchange-card rectangle, with a centered "+ Add Bybit" CTA and a two-line explanation. Reserves layout space so adding the first exchange doesn't reflow the page.

### Palette (Tailwind tokens map to these hex values)

| Token | Hex | Use |
|---|---|---|
| bg-page | `#0f1116` | page background |
| bg-card | `#141820` | card fill |
| bg-card-inner | `#0f1116` | nested panels inside a card |
| border-subtle | `#1c1f27` | card/row separators |
| border-strong | `#2a3040` | modal/drawer borders |
| text-primary | `#ffffff` | values, headings |
| text-secondary | `#aaaaaa` | body copy |
| text-muted | `#666666`–`#888888` | labels, timestamps |
| accent-green | `#4ade80` | positive P&L, Connected, LONG side |
| accent-red | `#ef4444` | negative P&L, KILL SWITCH, SHORT side, destructive actions |
| accent-amber | `#f7a600` | trail indicator, reconnecting, Bybit logo, TRAIL exit badge |
| accent-blue | `#1a73e8` | primary CTA, LS detector badge |
| accent-purple | `#8b5cf6` | TC detector badge |

Faded P&L / staleness uses `opacity: 0.7`. Kill-switch dim uses `opacity: 0.85` plus `filter: grayscale(0.3)`.

### `ExchangeCard` — structure (top to bottom)

1. **Header** (flex row): `[Bybit orange logo 32×32] [Bybit / Connected · Demo · v5 green dot]  ——  [Auto-trade toggle] [KILL SWITCH / DISARM button] [⚙ Settings]`
2. **Equity strip** (5 cards, `grid-template-columns: repeat(5, 1fr)`): Equity · Avail. margin · Open P&L · Today realized · Positions.
3. **Open positions table** — grid-columns (px): `100 60 90 90 90 90 80 1fr 24` for `Symbol / Side / Entry / Current / Stop / Target / P&L / Why / ⋯`. Row click opens `WhyModal`. Stop cell shows `94,420 TRAIL +1R` in amber when `trailTriggeredAt != null`.
4. **Recent closed (24h)** — compact list, `Symbol Side  —  [badge] +1.2R · +$12` per row. Badge: `TARGET` (green), `TRAIL` (amber), `STOP` (red). Click "see all" to open existing trade-ledger modal filtered to this account.

### Kill-switch engaged visual

- **Red banner** across the top of the card: `🛑 KILL SWITCH ACTIVE — no new positions` + `DISARM` button (red→green color flip on click).
- **Card body dimmed**: `opacity: 0.85`, `filter: grayscale(0.3)`. Position table remains readable and functional (close-one still works) because existing positions keep tracking trails and stops.
- **Footnote inside card**: `positions still tracked — only NEW signals blocked` (prevents misreading "dim card" as "my positions are gone").

### WebSocket connection indicator

- Header status dot: `green` when WS open, `amber pulsing` during reconnect (pulse 1.5s), `red solid` after >60s of failed reconnect.
- Status line: `● Connected · Demo · v5` → `● Reconnecting… (last update Xs ago)` → `● Disconnected (polling fallback)`.
- Values dim to `opacity: 0.7` while reconnecting. Polling fallback hits REST `/wallet` + `/positions` every 15s so numbers keep updating.
- Small `POLLING FALLBACK` pill in the header right when WS is down.
- **No toasts, no banners** for normal WS blips. Only if `> 60s` down, add an inline note under the header.

### Setup modal (AddExchangeButton → ExchangeSetupModal)

- **Single step**, 4 form fields in order: Environment toggle (DEMO selected, MAINNET disabled unless `execution.mainnet.enabled=true`), Label (optional), API key, API secret (masked input with show/hide).
- **Info box** above buttons: `On Bybit: Derivatives → Order + Position. NOT Withdraw. We reject withdraw-enabled keys.` (blue-left-border info style).
- **Mainnet-disabled warning**: amber-bordered info box above the environment toggle when server flag is off.
- **Validation errors** appear inline under the offending field (400 response body's `error` key determines which field).
- **Post-save**: close modal, new card slides into the stack, `autoTradeEnabled=false`, `killSwitch=true`. User must manually flip both to go live (2 explicit clicks).

### First-time auto-trade activation

- Modal on first `autoTradeEnabled: false → true` transition per account (keyed by `accountId` in localStorage under `execution.auto-trade-confirmed.<id>`).
- Content: `You're about to enable live trading on Bybit {Demo|Mainnet}. STRONG_BUY/STRONG_SELL signals will open real orders with real money. Risk/trade: {riskPercent}% of equity. Max concurrent: {maxConcurrentPositions}. Daily loss halt: {maxDailyLossPercent}%.` + `[ Cancel ] [ I understand, activate ]`.
- Subsequent toggles on the same account are silent.
- DEMO accounts still see the modal (rehearsal matters).

### Per-position row menu

- Trailing `⋯` column (24px). Click → popover menu with three items:
  1. `View in chart` → opens existing `TradeChartModal` scaffold filtered to this symbol+account.
  2. `Why this trade?` → opens `WhyModal` (same as clicking the row's Why badge).
  3. `Close at market` — red text, `border-top: 1px solid border-subtle` above it to separate from read actions. Click → small confirmation: `Close {symbol} {direction} at market? ({qty} @ ${currentPrice})` → `POST /trades/{id}/close`.
- `Close at market` is the only destructive action in the menu. Two barriers to fire (open menu + confirm).

### Settings side-panel

- Opens from right via the header `⚙` gear. Panel is `~240px` wide, slides in over the right third of the card, with the rest of the card dimmed to `opacity: 0.55` but still visible (positions stay scannable).
- Dismiss: `×` button top-right, Escape key, or click outside.
- 7 fields, one per row (top to bottom): Risk / trade (%), Leverage (x), Max concurrent, Daily loss halt (%), Signal max age (s), Position max age (h), Flip persistence (ticks).
- **Save / Cancel** buttons at bottom. Save issues `PATCH /accounts/{id}` with only-changed fields. Inline error if PATCH fails.

### WhyModal content

Reuses the existing `AiAnalysisModal` component scaffold (modal chrome, close behavior, scroll). Body renders:

1. Signal context: `BTCUSDT LONG · strategy: liquidity-sweep · regime: BULL · alignment: 72`.
2. 6-dimension scores (existing dimension gauge component from `SignalDashboard`).
3. Trade levels: entry / stop / target / R:R.
4. Trail state: `current rung: 1.0R, dynamic stop: $94,420, trail activated at 2026-04-21 14:23 UTC`.
5. Fills (list of `execution` WS events for this trade).
6. AI analysis (if present in `signal_outcomes.ai_analysis` for the matching signal — joined server-side by `/api/execution/accounts/{id}/trades/{tradeId}/why`; phase 1 is a stub that returns `{note: "..."}` — frontend renders a placeholder when `signalSnapshot.note` is present).
