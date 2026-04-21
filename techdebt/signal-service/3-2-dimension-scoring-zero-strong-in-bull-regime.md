---
name: dimension-scoring zero STRONG labels in BULL regime
description: Dimension-scoring engine produced zero STRONG_BUY/STRONG_SELL labels in 12h of BULL regime data — only detectors emitted STRONG. Either thresholds are miscalibrated or the scoring stack genuinely can't reach STRONG in this regime.
type: project
---

# Dimension-scoring engine produced zero STRONG labels in 12 hours

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java` (determineSignalLabel) |
| Found during | Debugging why trade-execution-service placed zero orders in 12h despite 10+ signals appearing in the UI |
| Date | 2026-04-21 |

## Issue

Over 24 hours of BULL regime (2026-04-20 → 2026-04-21), `signal_outcomes` table breakdown by strategy/signal_type:

```
strategy           | signal_type | count
-------------------+-------------+-------
dimension-scoring  | BUY         |    11
liquidity-sweep    | STRONG_BUY  |     1
trend-continuation | BUY         |     3
trend-continuation | STRONG_BUY  |     7
```

The **dimension-scoring** engine — which is what publishes `signal=STRONG_BUY` inside the every-5s overview snapshot on `crypto:signals` — produced **zero STRONG labels** across 13 symbols × ~17,280 ticks. Every actionable dimension-scoring row was labeled `BUY`, never `STRONG_BUY`.

BULL-regime thresholds in `SignalEngine.determineSignalLabel`:
- `strongBuy≥70, buy≥40` (tightened vs CHOP/UNKNOWN's `strongBuy≥55`)
- `alignment≥60` required for STRONG

With BULL defaults, no symbol's `overallScore` crossed 70 in the sampled window.

## Risks

1. **Dead code path in production.** The dimension-scoring → Redis-pub/sub → trade-execution-service pipeline is wired correctly but empty. If detectors ever stopped firing, the execution engine would have zero signal sources in BULL.
2. **Regime calibration drift.** If the bar is too high in BULL, the same bar is probably too high in BEAR (`strongSell≤-55`). STRONG_SELL counts are also historically very low; may be the same root cause.
3. **UX confusion.** Dashboard shows `strongBuyCount: 0 / strongSellCount: 0` permanently, even when detectors are firing STRONG setups — because the counts come from dimension-scoring labels, not detector events. Users think "no strong signals" when 8 strong setups fired.

## Suggested Solutions

1. **Recalibrate BULL thresholds from data.** Pull the actual `overallScore` distribution across 12h of BULL regime. If the 90th percentile is ~60, dropping `strongBuy` to `≥60` (with `alignment≥60`) gives ~5% of ticks a STRONG label — about one per symbol per hour. Validate on 1-2 weeks of data before shipping.
2. **Lower the `alignment≥60` requirement separately from score threshold.** Alignment measures how many dimensions agree; in a strong trend all dimensions should agree anyway, so 60 is probably redundant with score=70.
3. **Surface detector STRONG_BUY in the overview counts.** Add `strongBuyCount` from both dimension-scoring AND detector fires in the cached `SignalOverview`. This makes the UI honest about the real STRONG signal rate without touching the scoring engine.

Option 3 is the cheapest mitigation for the UX symptom. Options 1+2 fix the underlying calibration. Both tracks should run in parallel.
