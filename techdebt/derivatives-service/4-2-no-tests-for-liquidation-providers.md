# No test infrastructure for derivatives-service (liquidation providers)

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `derivatives-service` (no `src/test`, no JUnit dependency) |
| Found during | liquidation feed repair + staleness monitor (2026-06-18) |
| Date | 2026-06-18 |

## Issue

`derivatives-service` has **no test infrastructure** — no `src/test`, no `quarkus-junit5`
dependency. The liquidation providers parse exchange-specific WebSocket frames whose
field names and shapes drift over time, and several such drifts broke production
silently this session:

- Binance legacy WS URL decommissioned → connected, zero data.
- Bybit `liquidation` topic deprecated → wrong topic, zero data.
- OKX price field is `bkPx`, not `px` → every frame threw inside a `debug`-logged
  catch and was discarded.

The new `FeedStalenessMonitor` now catches the *symptom* (a feed going stale) and a
`/api/derivatives/feed-health` endpoint exposes per-feed freshness — but a parse
regression should fail at PR time, not minutes-to-hours later in production.

## Risks

- A future exchange field/shape change re-introduces a silent parse failure; the only
  current backstop is the staleness monitor, which fires after the fact.

## Suggested Solutions

1. Add `quarkus-junit5` to the pom and a `src/test` tree.
2. Add a pure parse test per provider (Binance/OKX/Bybit) that feeds a **captured real
   WS frame** and asserts the produced `Liquidation` (exchange, symbol, normalized
   LONG/SHORT side, base-asset qty, notional). The OKX case must assert the `bkPx`
   field and contract→base conversion specifically.
3. Add a `LiquidationNormalizer` unit test (side mapping per venue + contract conversion).
