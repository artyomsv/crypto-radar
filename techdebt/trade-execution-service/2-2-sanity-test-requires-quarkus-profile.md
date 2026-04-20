# SanityTest requires a QuarkusTestProfile to boot

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `services/trade-execution-service/src/test/java/com/cryptoradar/execution/SanityTest.java` |
| Found during | Task 6 — WireMock integration tests |
| Date | 2026-04-20 |

## Issue

`SanityTest` uses `@QuarkusTest` without a profile. Quarkus tries to boot the full application but fails because:

1. `execution.master-key` resolves to an empty string from `${EXECUTION_MASTER_KEY:}` — Quarkus 3.x rejects empty string as a required String config property.
2. `bybit.rest-base-override.DEMO/MAINNET` (added in Task 6) have `defaultValue = ""` — same rejection.
3. No real DB or Redis available in the test environment.

As a result, `SanityTest` errors on boot, and Quarkus's test isolation mechanism then marks all subsequent `@QuarkusTest` tests in the same surefire run as skipped — including `BybitV5RestClientTest` which has a correct WireMockProfile. Tests pass in isolation (`-Dtest=BybitV5RestClientTest`) but fail when run as part of the full suite.

## Risks

- Full `mvn test` always fails, making CI red from the start.
- New `@QuarkusTest` tests get silently skipped when SanityTest runs first.
- Developer confusion: passing tests when run individually, failing in full suite.

## Suggested Solutions

1. **Add a `SanityTestProfile`** (easiest): Create a `QuarkusTestProfile` that provides `execution.master-key`, `bybit.rest-base-override.DEMO/MAINNET`, and disables the datasource + Redis. Annotate `SanityTest` with `@TestProfile(SanityTestProfile.class)`. The health endpoint (`/q/health/live`) is liveness only and does not require DB/Redis to be UP.

2. **Add `%test` profile properties** to `application.properties`: set `%test.execution.master-key` to a well-known test key, `%test.bybit.rest-base-override.DEMO=http://localhost:38099`, and `%test.quarkus.datasource.active=false`. This is simpler but hard-codes test config in a shared resource file.

3. **Use `@QuarkusTestResource`** with Testcontainers for TimescaleDB and Redis — correct for integration tests but expensive (pulls Docker images) and overkill for a simple liveness check.

Option 1 is preferred: minimal change, mirrors the pattern established in `BybitV5RestClientTest.WireMockProfile`.
