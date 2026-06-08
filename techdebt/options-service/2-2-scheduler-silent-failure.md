# Options collector storing zero contracts (max-expiry window too narrow)

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Trivial |
| Location | `services/options-service/src/main/resources/application.properties` line `options.max-expiry-days=4` |
| Found during | Performance fix on `/opportunities/enriched` 2026-06-07 |
| Date | 2026-06-07 |

## Issue

`option_snapshots.MAX(time)` froze at 2026-05-30 23:42 and stayed there. The endpoint perf fix shipped this session is correct (10s → 100ms warm) but it surfaces **stale data** because the data pipeline behind it has been silently dropping every poll cycle.

Root cause is NOT a code bug. The schedulers run, the Bybit HTTP API responds (718 BTC contracts returned successfully on a manual `POST /api/options/admin/poll/BTC`), but `options.max-expiry-days=4` filters every contract out.

Bybit's BTC option expiry ladder as of today (2026-06-07):

```
7JUN26   (today — likely already past expiry)
8JUN26   (+1d)  ← only one inside the 4-day window
12JUN26  (+5d)  ← rejected
19JUN26  (+12d) ← rejected
... weekly + monthly + quarterly out to 2027
```

So `collectTickers` returns at the silent `LOG.debugf("no in-window contracts ...")` path (line 78 of `OptionsCollectorService.java`) with `batch.isEmpty() == true`. The DB row count stays at zero, the snapshot table goes stale, the enriched endpoint's `latestForSymbols` returns an empty map, and every opportunity is marked STALE because spot has moved outside the original strike band over 7 days.

There were also `UnresolvedAddressException` errors in the log from a 2026-05-30 incident (transient DNS failure inside the container, likely Docker DNS init race). Those are RESOLVED — fresh `wget` from inside the container returns valid Bybit JSON. The DNS exceptions are red herring for the current data-freeze symptom.

## Risks

1. **All "live" enriched data is up to 7 days stale.** End user thinks they're looking at fresh option opportunities; they're looking at last week's setups.
2. **No new opportunities are being scored** because the snapshot input is empty.
3. **Telegram option-opportunity notifications have been silent** for the same window.
4. The fix is a one-line property change but it requires choosing a defensible window. The original 4-day choice was likely calibrated against an earlier Bybit option calendar that had more dense short-term expiries.

## Suggested fix (under 1 minute of work)

Bump `options.max-expiry-days` from `4` → `14` to capture the weekly Bybit ladder + the next monthly. This widens the in-window contract set from ~0–10 contracts to ~150–300, which is the same density the original 4-day window saw when Bybit's expiry calendar was more crowded.

```diff
- options.max-expiry-days=4
+ options.max-expiry-days=14
```

Then rebuild + restart options-service. Verify with:

```bash
curl -X POST http://localhost:31088/api/options/admin/poll/BTC
# Expected: {"underlying":"BTC","contractsStored": >0}
```

## Secondary improvement (optional)

`collectTickers` currently logs the "no in-window contracts" outcome at DEBUG. Promote to INFO with the count of total contracts received vs. stored so the silent-zero condition is visible at default log level:

```java
LOG.infof("no in-window contracts for %s (received %d, max %d days)",
        underlying, resp.result().list().size(), maxExpiryDays);
```

That single change would have shrunk the diagnosis time from "stare at logs" to "single grep" — recommend bundling with the property bump.
