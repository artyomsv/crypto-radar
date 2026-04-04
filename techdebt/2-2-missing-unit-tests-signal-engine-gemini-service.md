# Missing unit tests: SignalEngine and GeminiAnalysisService

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java`, `GeminiAnalysisService.java` |
| Found during | QA run — 2026-04-04 |
| Date | 2026-04-04 |

## Issue

`SignalEngine` was majorly rewritten (scoring algorithm, confidence computation,
signal label thresholds, trade level population) and `GeminiAnalysisService` is a
new file — neither has any tests. The `signal-service` test directory is empty.

### High-value testable logic with no external deps:

**SignalEngine** (pure math, no I/O):
- `scoreWhale()` — sample-size dampening (tradeCount < 15 scales score, >= 30 amplifies)
- `scoreDerivatives()` — graduated funding rate tiers (±0.0003 / ±0.0008 / ±0.0015)
- `computeConfidence()` — weighted strength, contradiction penalties, 15–90 cap
- `determineSignalLabel()` — combined score+confidence thresholds
- `determineAlertLevel()` — OPPORTUNITY vs WATCH vs NEUTRAL
- `populateTradeLevels()` — stop/target math, MIN_RR=5.0, ATR fallback to 2% of price
- `scoreSentiment()` — Fear & Greed graduated scale (≤10, ≤25, ≥75, ≥90)

**GeminiAnalysisService** (pure logic extractable without HTTP):
- `isEnabled()` — blank key → disabled
- `getCachedAnalysis()` — returns null for expired or absent entries
- Cache TTL expiry (300s) and cooldown (120s) logic

## Risks

- The scoring algorithm is the core product differentiator. Regressions in threshold
  logic (e.g. funding rate tiers, confidence cap) will silently produce wrong signals
  with no test failure to catch them.
- `populateTradeLevels()` has an ATR fallback (`price * 0.02`) and a MIN_RR of 5.0
  for swing trades — off-by-one bugs here cause systematically bad entry/stop/target
  recommendations to users.
- `GeminiAnalysisService` cache eviction and cooldown are untested; a regression could
  spam the Gemini API ($$$) or serve stale/expired analysis silently.

## Suggested Solutions

1. **Unit tests for SignalEngine** — no Spring context needed. Instantiate directly,
   pass `Map<String, Object>` inputs, assert on the returned `TradingSignal` and
   `DimensionScore` fields. Mirror the pattern of `WhaleAnalyticsCalculationTest`.
   Priority methods: `scoreWhale` (dampening), `scoreDerivatives` (tier thresholds),
   `computeConfidence` (contradiction penalty), `populateTradeLevels` (R:R math).

2. **Unit tests for GeminiAnalysisService cache logic** — inject a blank API key to
   disable HTTP. Test `isEnabled()`, `getCachedAnalysis()` returns null pre-population,
   returns value post-population, returns null after TTL expiry (manipulate
   `CachedAnalysis.createdAt` via reflection or a test subclass).

3. **No integration tests needed right now** — HTTP calls to Gemini and Redis publishing
   in `SignalService` require mocks or containers; defer until the service stabilises.
