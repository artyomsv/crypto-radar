# Liquidation feeds silently dead since 2026-04-27

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `derivatives-service/.../scheduler/DerivativesScheduler.java`, `provider/OkxLiquidationProvider.java`, `provider/BybitLiquidationProvider.java` |
| Found during | data-feed audit for the AI-probability approach |
| Date | 2026-06-18 |

## Issue

The `liquidations` table stopped receiving rows on 2026-04-27 ~14:27 UTC and has been
empty since. All 14 tracked symbols stopped at the same moment — a simultaneous
multi-provider failure, not a per-symbol data gap.

Root cause is **upstream protocol drift across all three liquidation WebSockets**, while
the shared sink (`DerivativesService.storeLiquidation`) is healthy:

- **Bybit** (`BybitLiquidationProvider`, `wss://stream.bybit.com/v5/public/linear`):
  repeated `java.net.ConnectException`; also the `liquidation` topic was deprecated by
  Bybit in favour of `allLiquidation` — subscription "succeeds" but no data flows.
- **OKX** (`OkxLiquidationProvider`, `wss://ws.okx.com:8443/ws/v5/public`): connects and
  subscribes, then closes with `4004 No data received in 30s` — the `liquidation-orders`
  channel subscription shape no longer returns pushes.
- **Binance** (`DerivativesScheduler`, `!forceOrder@arr`): WS connects ("Liquidation
  WebSocket connected") but `recordLiquidation` is never called — 0 messages in hours.
  Binance reachable (fapi ping 200), so not geo-blocked; the all-market force-order push
  has changed.

The failure is **silent**: WS connect logs look healthy, no ERROR surfaced, and nothing
alerts on "table received 0 rows in 7 weeks."

## Risks

- A whole market-data dimension (liquidation cascades / squeeze pressure) is missing —
  directly weakens the planned AI-probability feature, which would consume it as a feature.
- The silent-failure pattern (connected-but-no-data) will recur on any WS feed; without a
  staleness alarm it goes unnoticed for weeks.

## Suggested Solutions

1. Per-exchange protocol fix against current WS specs:
   - Bybit: subscribe `allLiquidation.<symbol>` (replaces deprecated `liquidation.<symbol>`).
   - OKX: re-verify `liquidation-orders` channel arg shape (instType=SWAP) per current v5 docs.
   - Binance: confirm `!forceOrder@arr` payload/availability; consider per-symbol
     `<symbol>@forceOrder` streams.
2. Add a **data-staleness monitor**: a scheduled check that WARNs when a feed table's
   `max(time)` is older than N minutes — turns silent death into a visible alert. (Pairs
   with the existing `2-2-scheduler-silent-failure` debt.)
3. Add a parse/integration test per provider with a captured real WS frame so a future
   format change fails a test, not production.
