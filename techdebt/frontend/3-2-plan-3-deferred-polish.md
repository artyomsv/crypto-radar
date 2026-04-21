# Plan 3 Portfolio UI — deferred polish items

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `frontend/src/components/portfolio/*`, `frontend/src/hooks/useExecutionStream.ts` |
| Found during | Plan 3 subagent-driven reviews (Tasks 3, 6, 7, 9, 10, 11, 12) |
| Date | 2026-04-21 |

## Issues deferred from Plan 3 review cycle

These items were flagged by the per-task code-quality reviewers as Minor (phase-1-acceptable) but worth addressing in a dedicated polish pass before Plan 3 features are relied upon in anger.

### A11y — portfolio modals + popover lack focus management

`ExchangeSetupModal`, `WhyModal`, `FirstTimeAutoTradeModal`, `PositionRowMenu`, and `SettingsPanel` all:
- Do not trap keyboard focus inside the overlay.
- Do not move initial focus to a sensible target (first input / primary action / Cancel).
- Do not emit `role="dialog"` + `aria-modal="true"` + `aria-labelledby` on the outer container.
- `PositionRowMenu` does not emit `role="menu"` + `role="menuitem"` on its items.

Effect: keyboard-only and screen-reader users cannot navigate these surfaces. Plan 3 mouse UX works cleanly; a11y was deferred as a batched follow-up.

Fix: add a small shared `FocusTrap` helper hook + apply roles consistently. ~2 hours.

### `SettingsPanel` + `ExchangeSetupModal` — `parseInt` / `parseFloat` leniency

`parseInt("3abc", 10)` returns `3` (not NaN); same for `parseFloat`. User typing `3abc` into Max concurrent silently sends `3` as the PATCH value. Backend `@Min(1)` / `@Positive` annotations catch the worst cases (negative, zero) with 400 errors that surface inline, but trailing-garbage-parsing is surprising behavior.

Fix: either reject via strict regex `^-?\d+(\.\d+)?$` before parse, or use `Number("3abc")` which returns `NaN` for trailing chars.

### `ExchangeCard.handleCloseAtMarket` — browser `alert()` on error

Phase-1 UX placeholder. Replace with a toast system when one is introduced for the frontend (existing `AlertToast.tsx` under `components/dashboard/` may be reusable).

### `PositionRowMenu` — popover does not follow scroll

Anchor rect is read once during render. If the user scrolls the page while the menu is open, the popover stays at its original viewport position. The click-outside handler closes it on any scroll-triggering interaction, but an explicit scroll listener + repositioning would be cleaner.

### Unwired: `TradeChartModal` for `View in chart` menu item

`ExchangeCard.handleViewChart` is currently a `console.log('chart modal — Task 13')` stub. The existing `TradeChartModal` in `components/dashboard/` draws signal-service outcome points; it needs either (a) an `accountId?: number` prop to filter to the current Bybit account's trades, or (b) a separate `BybitTradeChartModal` variant.

### Unwired: `TradeLedger` for `see all →` on RecentTradesList

Same as above — `onShowAll` is currently a `console.log`. `TradeLedger` shows cross-signal-service outcomes; it needs an `accountId` filter before being linked from the Bybit card so users don't see the full cross-signal-service ledger when they click "see all" from a Bybit card.

### `useExecutionStream` — refresh-on-every-WS-frame

Every valid JSON frame from `/ws/execution` triggers 4 parallel REST GETs (wallet + positions + trades + events). For low-volume Bybit execution topics this is fine, but under burst conditions it will hammer the api-gateway. Consider a trailing 250ms debounce.

## Suggested Solutions

Batch these into a single "Portfolio UI polish" milestone. Priority:

1. **A11y focus management** — highest impact, lowest isolation risk.
2. **TradeChartModal + TradeLedger accountId filter** — unlocks two navigation paths that currently dead-end.
3. **parseInt strict validation** — low effort, prevents surprising silent-truncation.
4. **Toast instead of alert()** — UX polish.
5. **Popover scroll-follow + WS refresh debounce** — only if observable in practice.
