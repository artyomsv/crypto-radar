# Outcome evaluator MVP limitations

| Field          | Value                                                                                 |
|----------------|---------------------------------------------------------------------------------------|
| Criticality    | Medium                                                                                |
| Complexity     | Small                                                                                 |
| Location       | `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java` |
| Found during   | Building the signal outcome feedback loop                                             |
| Date           | 2026-04-08                                                                            |

## Issue

Known limitations I consciously accepted in the first iteration of the outcome evaluator, listed so they're not forgotten:

1. **25-hour gap recovery window.** The evaluator fetches the last 1500 1m candles per run (≈25 hours). If the evaluator (or signal-service) is down longer than 25 hours, any stop/target hit that occurred in the unrecovered window is missed — the row stays PENDING until `MAX_HOLD` (7 days) and is then marked `EXPIRED` at the current price instead of at the true hit. Underreports hit rate after downtime.

2. **Same-bar stop+target collision is always scored as stop.** If a 1m bar's range contains both `stop_price` and `target_price`, we have no tick data to know which came first, so we conservatively assume stop. This is correct as a pessimistic convention but understates measured win rate compared to reality.

3. **No slippage, no fees.** Realized PnL and R-multiple assume perfect fills at exactly `stop_price` / `target_price`. Real fills have slippage (especially on stop hits in fast markets) and exchange fees (~5-10 bps round trip). Good enough for comparing strategies against each other, but overstates absolute P&L if projected to live trading.

4. **AI analysis may be null on outcome creation.** Gemini analysis is triggered on transition but completes asynchronously. The outcome row is persisted in the same cycle as the transition, so the first persistence often has `ai_analysis = null`. The tracker dedupes on subsequent cycles, so the AI rationale never backfills. Low-impact — rationale is a nice-to-have for the outcome row, not a metric input.

## Risks

- Gap recovery: after a long outage, metrics will look worse than reality (wins become EXPIRED at flat P&L or drawdown). Mitigated by uptime, not codified.
- Same-bar collision: scalping-style signals with stop and target inside a single minute's range will systematically look worse than they are. Low priority because the engine's swing-trade R:R targets (5:1) make this case rare.
- Slippage/fees: irrelevant for relative strategy comparison; only matters if someone treats these numbers as an absolute live P&L forecast.
- Null AI analysis: no functional impact, only cosmetic on the outcome detail view.

## Suggested Solutions

1. **Gap recovery.** Move the evaluator off bar count and on to time range: "fetch all 1m candles from `max(lastEvaluatedAt, firedAt)` to now". Requires a new market-data endpoint that accepts a `from` timestamp, or paging over the existing `limit` endpoint. Half a day of work.

2. **Same-bar disambiguation.** Fetch 5s or 1s candles (if market-data-service stores them — currently doesn't) for the specific minute where both levels collide. Or fall back to aggTrade data from Binance. Non-trivial; lower priority.

3. **Slippage/fees config.** Add `scheduler.outcome.slippage-bps` and `scheduler.outcome.fee-bps` properties. Subtract fees from R and move stop/target prices by slippage before computing P&L. Trivial (~30 min).

4. **AI backfill.** On each signal cycle where the signal is actionable AND an open PENDING outcome exists for this symbol/direction AND `ai_analysis` is null AND the AI cache is populated → update the outcome row. Add a new `updateAiAnalysis` method on the repository and call from `OutcomeTracker`. ~1 hour.
