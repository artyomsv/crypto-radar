# No staleness alarm on market-data feeds

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `derivatives-service` (liquidation providers), `options-service` (collectors), cross-cutting |
| Found during | data-feed audit + liquidation-feed repair (2026-06-18) |
| Date | 2026-06-18 |

## Issue

Three market-data feeds were dead for weeks with zero alerting and were only found by a
manual audit:

- `liquidations` — all WebSocket feeds stopped 2026-04-27 (Binance legacy URL
  decommissioned 04-23; Bybit `liquidation` topic deprecated for `allLiquidation`).
  **Fixed 2026-06-18** (Binance `/market` path, Bybit `allLiquidation` array parse, OKX
  subscribe-ack/error logging).
- `long_short_ratio` and `option_historical_vol` — silently wrote zero rows (separate fixes).

The common failure mode is **silent**: WebSockets log "connected" / collectors log
"refreshed N symbols" while persisting nothing. Nothing watches whether a table's newest
row is actually advancing.

## Risks

- A feed can die and stay dead for weeks, invisibly degrading every downstream consumer
  (signals, the planned AI-probability model) with no signal that data is missing.
- Re-occurs on any exchange API change; the providers reconnect happily to a dead stream.

## Suggested Solutions

1. **Staleness monitor** — a scheduled job that checks `max(time)` per feed table
   (`liquidations`, `funding_rates`, `open_interest`, `long_short_ratio`,
   `option_snapshots`, `option_historical_vol`, `candles`) and WARNs/emits a metric when a
   feed's freshness exceeds a per-feed threshold. Turns silent death into a visible alert.
   Overlaps with `2-2-scheduler-silent-failure` — consider solving together.
2. **Per-provider parse tests** — feed each liquidation provider a captured real WS frame
   and assert a `Liquidation` is produced, so a future exchange format change fails a test
   rather than production.
