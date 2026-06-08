# Walk-Forward Analysis

> Train on the past, test on the future, slide the window, repeat. The only backtesting protocol that maps directly onto how a strategy is actually deployed.

## Definition

Walk-forward analysis (WFA) is the time-series-respecting variant of train/test splits. The protocol:

1. Pick a window length `W_train` (in-sample) and `W_test` (out-of-sample).
2. Fit the model — or set the thresholds — on `[t0, t0 + W_train]`.
3. Evaluate on `[t0 + W_train, t0 + W_train + W_test]`. Record the OOS performance.
4. Slide forward: set `t0 := t0 + W_test` (rolling) or extend `W_train := W_train + W_test` (expanding). Repeat from step 2.
5. Aggregate the OOS performance segments — these are the only numbers worth quoting externally.

The protocol has two flavors:

- **Rolling-window** (also "anchored sliding"): `W_train` stays fixed, the window moves. Best when the data-generating process changes regimes and you want recent behavior to dominate.
- **Expanding-window** ("anchored"): `W_train` grows over time, always anchored at the start. Best when the process is roughly stationary and more data is better.

A correctly-executed WFA produces a sequence of OOS Sharpe / R-multiple / drawdown statistics, one per fold. The **distribution** of those is the deliverable, not the average. A strategy with mean OOS Sharpe of 1.2 and standard deviation 1.8 across 12 folds is unreliable; a strategy with mean OOS Sharpe 0.8 and std 0.2 across 12 folds is publishable.

WFA is the simplest non-leaking protocol. Purged k-fold (`05-overfitting-and-cv.md`) gives you more evaluation samples for the same data; CPCV gives you more still. WFA is the floor.

## When it works

- **You have ≥ 12 folds of out-of-sample data.** Below that, your OOS distribution is too narrow to distinguish skill from luck.
- **The strategy has a clear training step.** Either a fitted model or a parameter sweep. If neither — if your thresholds are hand-set on judgment — WFA degenerates to "did the live engine do well in segment N?" and stops being WFA. (That said, the discipline of evaluating per-segment is still useful.)
- **Costs are modeled.** Every WFA fold has to compute net-of-fees and net-of-slippage outcomes. A fee-blind WFA looks fine and breaks in production.

## When it fails

- **W_test too small.** Below ~30 OOS observations per fold, fold-level variance dominates. Crypto at 13 symbols × ~2 signals/symbol/day, you need at least 10–14 days of OOS per fold.
- **W_train too small.** The training window has to span both volatility regimes the model will see in OOS. A 30-day training window on data that only happens to cover a chop period will fit chop and break on the next breakout.
- **Regime change within OOS.** A fold that spans a BULL → BEAR flip will show mixed performance and look worse than it is (or hide a real regression). Regime-conditioned WFA — bucket folds by `MarketRegime` — is the fix.
- **Repeated tuning across folds.** "We tried 5 hyperparameters in fold 1, picked the winner, fed it to fold 2…" — the tuning is itself an OOS leak. WFA must use the same hyperparameters across all folds, or the tuning has to be redone *within* each in-sample window with no peek at the OOS.
- **Survivorship in symbol selection.** A WFA on the 13 currently-trading pairs ignores the fact that delisted symbols are missing. Less of an issue for us since we trade a small fixed universe, but real for any "backtested on all coins that existed at the start of 2024" claim.
- **Treating WFA as proof of edge.** Even a clean WFA is one experiment. It does not deflate for the configurations you tried before getting here. WFA + Deflated Sharpe is the full protocol (`06-deflated-sharpe.md`).

## What we do today (in projectr-x)

We do not do formal walk-forward analysis. What we do is something simpler and more pragmatic, and it is worth being explicit about the limitations.

**What we have:** A `deployment_markers` audit trail with four anchors:

| Marker | Date | Description |
|---|---|---|
| v1-initial-fixes | 2026-04-19 20:00 UTC | bias removal, stop-distance guard, LS filter tightening, MIN_RR |
| v2-trail-system | 2026-04-19 23:30 UTC | trailing stop ladder, final_exit_reason, fees |
| v3-full-rollout | 2026-04-20 01:00 UTC | regime detection, LS volume, alignment |
| v4-data-driven-vectors | 2026-04-24 00:00 UTC | derivatives unit fix, news sentiment, orderbook name, exec gates, stagnation, trail second-rung |

Every slice of `signal_outcomes` between two markers is a "fold" — fixed-thresholds, varying market. The metrics endpoint (`GET /api/signals/metrics?periodDays=N`) reads from this.

**What's wrong with that as WFA:**

1. The folds are not equal-sized. v1, v2, v3 are hours apart; v4 is 5 days after v3 and counting. Per-fold variance is uncomparable.
2. The thresholds are *not* fit in-sample on each fold; they are hand-revised based on observation of the *previous* fold. That is precisely the WFA protocol — but only if we are disciplined about not peeking at the *next* fold's data before revising.
3. **The 14-day post-v4 OOS window is too thin for strong claims.** With 272 closed signals across 13 symbols across 4 strategies, per-cell sample sizes are 5–28. The Central Limit Theorem floor is n = 30. We are below it for every per-symbol-per-strategy cell. The `+32R total / +0.118R per signal` finding is real, but the per-strategy breakdowns are anecdote-grade until we cross n=30 per cell — probably mid-July at current fire rates.

**Implication:** any claim of "we found an edge with v4" has to be qualified with "on n < 300 trades across 6 weeks." We should resist the temptation to declare victory and ship more aggressive thresholds until we have at least 2× more data — that's 1–2 more `deployment_markers` cycles of patience.

Code references:
- `db/init/signal-init.sql` — `deployment_markers` table, schema for the marker / version / description triple.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/PerformanceMetricsService.java` — the metric slicer that consumes markers.
- `services/signal-service/src/main/java/com/cryptoradar/signal/resource/SignalResource.java` — endpoints `GET /metrics` and `GET /deployments`.

## Implementation sketch

A formal WFA endpoint would:

1. Read `deployment_markers` to get fold boundaries.
2. For each fold, pull `signal_outcomes` rows where `fired_at` ∈ [marker_N, marker_{N+1}].
3. Compute net-of-fees R-multiples per signal, then per-fold totals: count, totalR, avgR, win-rate, stop-rate, target-rate, trail-rate, stagnation-rate, max drawdown in R-multiples.
4. Compute Sharpe = avg_R / std_R × √(signals_per_year).
5. Return the per-fold time series and the distribution statistics across folds.

Acceptance criteria before any aggressive threshold change:

- ≥ 6 folds of meaningful (n ≥ 50) post-marker data.
- Per-fold OOS Sharpe std-dev < 1.0 (consistent edge).
- Mean OOS Sharpe ≥ 0.8 deflated by trial count (see DSR doc).

Effort: ~1 day to implement the endpoint, then a 2–3 month wait to accumulate the data.

## Sources

1. [Lopez de Prado, M. *Advances in Financial Machine Learning*, Chapter 12 "Backtesting Through Cross-Validation"](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086) — the chapter that places WFA in the cross-validation hierarchy and argues why it's the floor, not the ceiling.
2. [Pardo, R. *The Evaluation and Optimization of Trading Strategies*, 2nd ed. (Wiley, 2008)](https://www.wiley.com/en-us/The+Evaluation+and+Optimization+of+Trading+Strategies%2C+2nd+Edition-p-9780470128015) — the canonical practitioner book on WFA; Pardo coined the modern formalization of the protocol.
3. [Aronson, D. *Evidence-Based Technical Analysis* (Wiley, 2007)](https://www.wiley.com/en-us/Evidence+Based+Technical+Analysis%3A+Applying+the+Scientific+Method+and+Statistical+Inference+to+Trading+Signals-p-9780470008744) — companion reading on the statistical-testing side of WFA, with sample-size guidance.
4. [QuantConnect, "Walk-Forward Optimization"](https://www.quantconnect.com/docs/v2/research-environment/applying-research/walk-forward-optimization) — practitioner reference for the rolling vs anchored variants and parameter selection.
5. [InsightBig, "Traditional Backtesting is Outdated. Use CPCV Instead"](https://www.insightbig.com/post/traditional-backtesting-is-outdated-use-cpcv-instead) — counter-argument for going beyond WFA when sample size permits.
