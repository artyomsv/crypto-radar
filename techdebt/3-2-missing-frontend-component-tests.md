# Missing frontend component and API client tests

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `frontend/src/components/dashboard/CryptoConfig.tsx`, `frontend/src/components/dashboard/WhaleTracker.tsx`, `frontend/src/components/dashboard/CryptoDetailView.tsx`, `frontend/src/lib/api.ts` |
| Found during | QA coverage check — recent commit batch |
| Date | 2026-04-03 |

## Issue

The frontend has no test files at all — no `*.test.tsx`, no `__tests__/` directory, no Vitest configuration detected. Three dashboard components were changed in this commit batch and none have tests:

- `CryptoConfig.tsx` — complex component with CRUD interactions (add/toggle/delete cryptos, edit backfill depths), API calls, and conditional rendering
- `WhaleTracker.tsx` — renders trade distribution and analytics data from `useWhaleData` hook
- `CryptoDetailView.tsx` — presumably renders crypto detail data

The `api.ts` module added several new methods (`getConfigCryptos`, `addCrypto`, `toggleCrypto`, `removeCrypto`, `searchSymbols`, `getBackfillConfig`, `updateBackfillDepth`) with no tests.

## Risks

- `CryptoConfig.tsx` has 8+ `useState` calls and several async handlers — regressions in add/delete/toggle flows are invisible.
- `api.ts` methods like `addCrypto` and `removeCrypto` use raw `fetch` (not `fetchJson`) and don't handle non-OK responses — error handling gaps are unverified.
- The `searchSymbols` debounce logic (if any) is untested.

## Suggested Solutions

1. **Set up Vitest + Testing Library** if not already configured (`vitest`, `@testing-library/react`, `@testing-library/user-event`, `msw` for API mocking).

2. **Test `api.ts`** — mock `fetch` and assert each method calls the correct URL with the correct method/body, and that `fetchJson` returns null on non-OK responses.

3. **Test `CryptoConfig`** at minimum:
   - Renders the list of cryptos from a mocked API response
   - Shows loading state initially
   - `removeCrypto` calls the DELETE endpoint and removes the item from the list

4. **Test `WhaleTracker`** — renders without crashing given mocked `useWhaleData` output.
