# OKX liquidation feed delivers no data despite confirmed subscription

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `derivatives-service/.../provider/OkxLiquidationProvider.java` |
| Found during | liquidation multi-source normalization (2026-06-18) |
| Date | 2026-06-18 |

## Issue

The OKX provider connects to `wss://ws.okx.com:8443/ws/v5/public`, subscribes to
`liquidation-orders`/SWAP, and OKX **confirms** the subscription (logged
`event:"subscribe"`). The connection stays up (ping/pong working, no idle close).
But **zero liquidation rows arrive** — verified over 10+ minutes with the new
`exchange` column showing `BINANCE` and `BYBIT` flowing while `OKX` stays at 0.

Since the channel subscribes to all SWAP instruments market-wide, a real feed
should produce a steady stream. Zero means OKX accepts the subscription but does
not push on this endpoint/channel combination. (This was always the case — the
earlier "liquidations restored" was Binance + Bybit only; it was invisible until
the `exchange` column was added.)

## Risks

- One of three liquidation sources contributes nothing — the cross-venue aggregate
  is really Binance + Bybit. Binance dominates volume so impact is limited, but the
  data is less complete than it appears.

## Suggested Solutions

1. Try the OKX **business** WebSocket endpoint (`wss://ws.okx.com:8443/ws/v5/business`)
   — OKX migrated several channels off `/public`; `liquidation-orders` may now require
   business.
2. Verify the current `liquidation-orders` arg shape against the live OKX v5 docs /
   changelog (possible new required field or `instType: ANY`).
3. If OKX no longer offers a usable public liquidation stream, drop the provider and
   document Binance + Bybit as the liquidation sources.
