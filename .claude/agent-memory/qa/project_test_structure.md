---
name: CryptoRadar test structure
description: What test infrastructure exists and what is missing across all six services in the CryptoRadar monorepo — verified 2026-04-03
type: project
---

Tests exist only in whale-service as of 2026-04-03.

**Why:** The project is a prototype; only whale-service has accumulated unit tests so far.

**How to apply:** When running QA for any service other than whale-service, expect zero existing tests. The highest-value untested classes with pure logic (no DB/network deps) are IndicatorCalculator (analytics-service), SentimentAnalyzer, and SymbolExtractor (news-service) — these should be the first tests written.

## whale-service (has tests — 30 tests, all passing)
- `src/test/java/com/cryptoradar/whale/model/WhaleTransactionTest.java` — 4 tests for `WhaleTransaction.fromBinanceTrade()`
- `src/test/java/com/cryptoradar/whale/model/WhaleFlowSummaryTest.java` — 3 tests for `WhaleFlowSummary` constructor and flow direction
- `src/test/java/com/cryptoradar/whale/service/WhaleAnalyticsCalculationTest.java` — 23 tests for pressure formula, pressure labels, activity score, net flow math

Tests use plain JUnit 5 with `assertEquals`/`assertTrue` (no Mockito, no Spring context). No `@ExtendWith`, `@DisplayName`, or `@Nested` — pattern compliance gaps exist but tests run clean. WhaleAnalyticsCalculationTest mirrors private logic via duplicated helper methods rather than testing the class directly — drift risk if formulas change in WhaleAnalyticsService.

## market-data-service — ZERO tests
Highest-value untested classes:
- `BinanceRateLimiter` — backoff/rate-window state machine (hard to test due to Thread.sleep)
- `MarketDataService` — DB-backed, needs integration test

## news-service — ZERO tests
Highest-value untested classes (pure logic, no deps):
- `SentimentAnalyzer` — keyword scoring, label thresholds, edge cases (null/empty input)
- `SymbolExtractor` — keyword-to-symbol mapping (static utility)

## analytics-service — ZERO tests
Highest-value untested classes (pure logic, no deps):
- `IndicatorCalculator` — RSI, EMA, SMA, MACD, Bollinger Bands, ATR, support/resistance (complex math, most critical)

## api-gateway — ZERO tests
Only `AggregationService` has custom logic worth unit testing.

## frontend — ZERO tests (no Vitest config found)
`useDashboardData` and `useWebSocket` hooks have the most logic but require browser API mocking.

## How to run whale-service tests
```bash
docker run --rm -v "E:/Projects/Stukans/Prototypes/projectr-x/services/whale-service:/build" -w //build maven:3.9-eclipse-temurin-21-alpine mvn test -q
```
Image `maven:3.9-eclipse-temurin-21-alpine` is already pulled locally.
30 tests, all pass. Runtime ~37s (warm Docker image).
