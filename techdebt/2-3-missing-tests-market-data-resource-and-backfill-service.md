# Missing integration tests for MarketDataResource and BackfillService

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `services/market-data-service/src/main/java/com/cryptoradar/marketdata/resource/MarketDataResource.java`, `services/market-data-service/src/main/java/com/cryptoradar/marketdata/service/BackfillService.java` |
| Found during | QA coverage check — recent commit batch |
| Date | 2026-04-03 |

## Issue

`market-data-service` has zero test files. Both changed files are substantive:

- `MarketDataResource` has 10+ endpoints including a full CRUD surface for crypto configuration (add, toggle, delete cryptos; update backfill depth). The `addCrypto` endpoint calls Binance, manipulates DB state, and fires a virtual thread backfill — all untested.
- `BackfillService.backfill()` contains branching gap-detection logic (backward fill, forward fill, no-data full fill) and pagination (BINANCE_MAX_LIMIT batching) — none of this logic is covered.

Specific untested behaviors:
- `addCrypto` validation: symbol not ending in USDT gets `USDT` appended
- `addCrypto` validation: symbol not found on Binance returns 400
- `removeCrypto` with `deleteData=true` deletes candles and price_snapshots
- `updateBackfillConfig` rejects `depthDays` outside 1-5000 range
- `BackfillService.backfill()` correctly detects and fills backward gaps
- `BackfillService.fetchRange()` paginates correctly when batch size == 1000
- `BackfillService.backfill()` skips forward fill when data is current

## Risks

- The 400/404 error branches in the resource are completely unverified; a typo in SQL or condition inversion would pass code review undetected.
- `BackfillService` silently swallows all DB exceptions (returning 0 or null); broken SQL queries produce no test signal.
- The `deleteData` path in `removeCrypto` deletes candles — a bug here is destructive.

## Suggested Solutions

1. **Unit-test `BackfillService`** with mocked `BinanceClient`, `EntityManager`, `AgroalDataSource`, and `MarketDataService`. Test the three backfill branches (no data, backward gap, forward gap) and the pagination loop.

2. **Integration-test `MarketDataResource`** using Quarkus `@QuarkusTest` + REST-assured against a test DB (H2 or Testcontainers PostgreSQL). Cover at minimum:
   - `POST /api/market/config/cryptos` — happy path and Binance-not-found 400
   - `PUT /api/market/config/backfill/{interval}` — valid and out-of-range depthDays
   - `DELETE /api/market/config/cryptos/{symbol}?deleteData=true` — data actually deleted
   - `GET /api/market/config/cryptos` — returns expected shape
