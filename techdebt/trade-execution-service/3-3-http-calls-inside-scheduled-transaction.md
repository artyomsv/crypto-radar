# HTTP calls inside scheduled @Transactional sweeps

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/DonchianExitMonitor.java` (sweep), `StagnationMonitor.java` (sweep) |
| Found during | Plan 2 code review (live Turtle/Donchian execution) |
| Date | 2026-06-11 |

## Issue

`DonchianExitMonitor.sweep()` is `@Transactional` and issues blocking HTTP calls
(`MarketDataClient.getDailyCandles` + `getLastPrice`, up to `HTTP_TIMEOUT`=3s each)
per open long-horizon trade inside the open transaction. The Hibernate/Agroal
connection is held from `accountRepo.listAll()` through every per-trade HTTP
round-trip until the method returns. `StagnationMonitor.sweep()` shares the
pattern but issues only fast DB queries; `DonchianExitMonitor` adds slow external
HTTP, making the connection-pinning materially worse.

## Risks

At low open-trade counts (Plan 2 steady state: ~3-5 long-horizon trades) this is
safe — worst case ~2 × N × 3s of connection holding per 60s tick against a
10-connection Agroal pool. As Plan 3 pyramiding raises per-symbol trade counts,
or if market-data latency spikes, the held connections could approach pool
exhaustion and stall unrelated execution work.

## Suggested Solutions

1. Two-phase sweep: fetch all candles/prices OUTSIDE any transaction, then open a
   short transaction only for the `orderPlacer.close(...)` writes (re-load the
   trade in the tx). Removes HTTP from the connection-holding window.
2. Cache daily candles per symbol within a tick (and short-TTL across ticks) so N
   trades on M symbols do M fetches, not N.
3. Apply the same refactor to `StagnationMonitor` for consistency.
