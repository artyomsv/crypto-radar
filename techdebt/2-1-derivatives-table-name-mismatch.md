# Derivatives-service queries tables that don't exist

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Trivial |
| Location | `services/derivatives-service/` (SQL queries) + `db/init/derivatives-init.sql` |
| Found during | PR2 redeploy — `docker compose restart` produced a wall of `ERROR:  relation "derivatives_open_interest" does not exist` from timescaledb |
| Date | 2026-04-20 |

## Issue

`derivatives-service` issues queries against tables named `derivatives_open_interest` and `derivatives_funding_rates`, but `db/init/derivatives-init.sql` creates them as `open_interest` and `funding_rates` (no `derivatives_` prefix).

Every call cascades into PSQLException → DB logs spam `ERROR:  relation "derivatives_open_interest" does not exist at character 13`.

The bug is pre-existing (unrelated to PR1/PR2/PR3 work). It was hidden because:
- Derivatives endpoint responses may be silently returning empty/default values
- Log scanning wasn't done during the multi-session buildout

The running DB does contain real data in `open_interest` and `funding_rates` (verified on 2026-04-19 analysis: 24 kB funding_rates, 24 kB open_interest rows), but derivatives-service's queries hit the wrong names and return nothing.

## Risks

- Derivatives dimension in signal scoring may be receiving partial or fallback data, skewing downstream signals.
- Frontend derivatives views likely show empty/stale data without users realizing.
- Log noise obscures real errors — the restart produced ~60 lines of the same error before any actual app error surfaced.

## Suggested Solutions

1. **Preferred**: rename the queries in derivatives-service to match the existing schema (`open_interest`, `funding_rates`). One grep + rename per class. Table names are plain and accurate already.
2. Alternative: rename tables via migration to `derivatives_*`. Heavier because timescaledb hypertables and continuous aggregates must be reconsidered. Avoid unless there's a naming-convention reason to prefix domain-owned tables.

Check every query against the derivatives tables — confirm all call sites use the correct names.
