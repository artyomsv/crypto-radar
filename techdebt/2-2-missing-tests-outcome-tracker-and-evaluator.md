# Missing unit tests for OutcomeTracker and OutcomeEvaluator

| Field          | Value                                                                                             |
|----------------|---------------------------------------------------------------------------------------------------|
| Criticality    | High                                                                                              |
| Complexity     | Small                                                                                             |
| Location       | `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeTracker.java`, `OutcomeEvaluator.java` |
| Found during   | Building the signal outcome feedback loop                                                         |
| Date           | 2026-04-08                                                                                        |

## Issue

`OutcomeTracker` and `OutcomeEvaluator` are the heart of the signal-quality feedback loop — they decide which signals get recorded and how each trade's outcome is computed. Neither has unit tests. The code was smoke-tested end-to-end against a synthetic row in the running DB (and passed), but that is not a substitute for unit tests covering edge cases.

Critical untested branches:

**OutcomeEvaluator**
- Both stop and target triggered inside the same 1m bar → should return `HIT_STOP` (pessimistic convention).
- Empty bars list → should be a no-op, not expire.
- MFE/MAE sign conventions for LONG vs SHORT (easy to flip).
- Evaluator called on an outcome whose `lastEvaluatedAt` is in the future of all fetched bars → should skip cleanly.
- Expiry path: outcome is older than `MAX_HOLD` and neither level was hit → should close at last bar's close price with `status=EXPIRED` and compute realized PnL.
- `risk = 0` guard (entry == stop) in `closeOutcome()` → should not divide by zero.

**OutcomeTracker**
- Dedup: attempting to track a second LONG signal for the same symbol while one is already PENDING should NOT insert a second row.
- Signal missing entry/stop/target → should silently skip (no NPE).
- Signal type `NEUTRAL` reaching the tracker → should skip (belt-and-suspenders — SignalService already filters this out).
- Dimension name mismatch (e.g. engine renames `"OrderBook"` to `"Orderbook"`) → should not fail, unknown dimensions are ignored but the score is silently lost. May warrant a warn log so drift is visible.

## Risks

The outcome tracker is the measurement system that will drive every future decision about signal quality. If it silently under-counts wins, over-counts losses, or miscomputes R-multiples, every downstream conclusion becomes wrong. Bugs here are especially dangerous because they're hard to spot — the numbers will still look plausible, just slightly off, and we'll make strategy decisions based on them.

Specific failure modes:
- Sign-convention bug in MFE/MAE for SHORT → analysis says short signals have tiny drawdowns when they actually have huge ones.
- Same-bar stop/target convention bug → win rate inflates because ambiguous outcomes are scored as wins.
- Dedup bug → same trade idea counted multiple times, inflating sample size.

## Suggested Solutions

1. **Pure-function refactor + unit tests (recommended).** Extract the bar-walking logic in `OutcomeEvaluator.evaluateOne()` into a static or injectable helper taking `(SignalOutcome, List<CandleBar>)` and returning an `EvaluationResult` record. Then test exhaustively without any DB/HTTP. Cover: LONG hit target, LONG hit stop, LONG same-bar both, SHORT hit target, SHORT hit stop, SHORT same-bar both, neither hit, empty bars, expired. ~15 test cases, ~1-2 hours of work.

2. **Integration tests with testcontainers.** Spin up a real TimescaleDB, insert synthetic outcomes and candles, run the full evaluator, assert the row state. Higher-fidelity but slower and more brittle. Reserve for later.

3. **Golden dataset regression.** Capture a day's worth of real 1m candles for a symbol, define 5-10 hand-labeled expected outcomes, replay through the evaluator, and snapshot the results. Makes future refactors safe. Follow-up to (1).
