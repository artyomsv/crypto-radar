# Hardcoded JDBC URL in application.properties blocks test isolation

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `services/trade-execution-service/src/main/resources/application.properties:11` |
| Found during | Plan 2b Task 1 spec review |
| Date | 2026-04-20 |

## Issue

`services/trade-execution-service/src/main/resources/application.properties` line 11 hardcodes the JDBC URL:

```properties
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5433/marketdata
```

with no `${...}` indirection. This preempts Quarkus Dev Services (which would auto-provision a Testcontainers PostgreSQL for tests) and prevents `%test.quarkus.datasource.db-kind=h2` from being applied via profile overlay, because the base property wins.

Downstream effect observed in `AccountResourceTest`:
- The test's `@TestProfile` cannot redirect the datasource at all; the profile can only override `bybit.rest-base-override.DEMO`, `execution.master-key`, etc.
- Tests fall back to the running `projectr-x-timescaledb-1` container on host port 31432, which:
  - Requires `docker compose up` to have been run before `mvn test` — tests will fail in a clean CI environment with no container running.
  - Pollutes a shared `exchange_accounts` / `executed_trades` / `execution_events` state across test classes, mitigated only by per-test `deleteAll()` in `@BeforeEach`/`@AfterEach` (any test that forgets this cleanup corrupts subsequent runs).
  - Runs integration-style tests against the same DB production code points at locally — side-effects from a flaky test could break dev workflow.

Same problem also applies to Plan 2b Task 4, 5, 6, 7 WireMock-based tests — all of them will have the same datasource shortcoming until this is fixed.

## Risks

- **CI breaks on day one.** First time this service's Maven tests run in CI (GitHub Actions or similar), they fail because no TimescaleDB container is present at the expected host:port.
- **Cross-test pollution.** Any test that persists an entity and fails to clean up leaks state into the next test class. Debug sessions will surface as "tests pass in isolation, fail when run together".
- **No clean path to `@DataJpaTest`-style tests.** The current setup forces every persistence test to boot the full Quarkus app + hit the shared DB, slowing the feedback loop.

## Suggested Solutions

### Option 1 — Environment-variable indirection (minimal change, fixes CI)

Change `application.properties` line 11 to:

```properties
quarkus.datasource.jdbc.url=${QUARKUS_DATASOURCE_JDBC_URL:jdbc:postgresql://localhost:5433/marketdata}
```

Then tests can set `QUARKUS_DATASOURCE_JDBC_URL` via `@TestProfile.getConfigOverrides()` returning a Testcontainers-provisioned URL. This is the smallest-delta fix.

Pro: 1-line change, test profile can point at anything.
Con: tests still need to provision some database (Testcontainers or a CI-side service container).

### Option 2 — Quarkus Dev Services + profile-split application.properties

1. Remove the hardcoded URL from `application.properties`.
2. Add `application-prod.properties` with the production JDBC URL.
3. In `application.properties` put only dev/test-safe settings and let Quarkus Dev Services (`quarkus-datasource.devservices.enabled=true`) spin up a Postgres Testcontainer for `%dev` and `%test` profiles.

Pro: fully isolated tests, no external container dependency in CI or local dev.
Con: ~4 files change; all tests that assumed the shared DB will need to re-run their migrations (`db/init/execution-init.sql` has to be mounted into the Testcontainer).

### Option 3 — `%test`-profile H2 override (fastest unit tests)

Add to `application.properties`:

```properties
%test.quarkus.datasource.db-kind=h2
%test.quarkus.datasource.jdbc.url=jdbc:h2:mem:trade-execution;DB_CLOSE_DELAY=-1
%test.quarkus.datasource.username=sa
%test.quarkus.datasource.password=
%test.quarkus.hibernate-orm.database.generation=drop-and-create
```

Pro: ~0.5s Quarkus startup, no Docker needed for tests.
Con: H2 doesn't support JSONB, TimescaleDB hypertables, or `@JdbcTypeCode(SqlTypes.JSON)` on `execution_events.metadata`. Will need a per-entity test-profile replacement. Most realistic only for pure REST-layer tests that don't write JSONB.

### Recommendation

Start with **Option 1** (1-line fix) to unblock CI and let Plan 2b Tasks 4-11 use proper per-test containers via Testcontainers in their `@TestProfile`. Re-evaluate Option 2 when the service has 5+ integration-test classes.
