---
name: CryptoRadar test structure
description: What test infrastructure exists and what is missing across services in the CryptoRadar monorepo
type: project
---

Tests exist only in whale-service as of 2026-04-03.

**Why:** The project is a prototype; only whale-service has accumulated unit tests so far.

**How to apply:** When running QA for market-data-service, api-gateway, or the frontend, expect zero existing tests — flag all changed production files as MISSING coverage and file techdebt entries. Do not waste time grepping for tests in those services.

## whale-service (has tests)
- `src/test/java/com/cryptoradar/whale/model/WhaleTransactionTest.java` — 4 tests for `WhaleTransaction.fromBinanceTrade()`
- `src/test/java/com/cryptoradar/whale/model/WhaleFlowSummaryTest.java` — 3 tests for `WhaleFlowSummary` constructor
- `src/test/java/com/cryptoradar/whale/service/WhaleAnalyticsCalculationTest.java` — 23 tests for pressure/label/activity math formulas

Tests use plain JUnit 5 with `assertEquals`/`assertTrue` (no Mockito, no Spring context). No `@ExtendWith`, `@DisplayName`, or `@Nested` — pattern compliance gaps exist but tests run clean.

## market-data-service — ZERO tests
## api-gateway — ZERO tests
## frontend — ZERO tests (no Vitest config found)

## How to run whale-service tests
```bash
docker run --rm -v "E:/Projects/Stukans/Prototypes/projectr-x/services/whale-service:/build" -w //build maven:3.9-eclipse-temurin-21-alpine mvn test -q
```
Image `maven:3.9-eclipse-temurin-21-alpine` is already pulled locally.
30 tests, all pass. Runtime ~38s.
