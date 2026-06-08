# Overfitting, Cross-Validation, and Why Standard CV Fails on Time Series

> Standard k-fold CV is a lie on financial data. Purged k-fold with embargo, and walk-forward, are the only validators that don't quietly leak the future into your training set.

## Definition

**Overfitting** is the phenomenon of a model performing better on the data used to train and tune it than on data it has never seen. In trading the consequence is direct and painful: a backtest with Sharpe 2.5 that returns Sharpe 0.3 in live trading is overfit; you cannot recover the missing 2.2 by tweaking anything.

**Cross-validation (CV)** is the standard defense: hold out part of the data, train on the rest, evaluate on the held-out part, repeat. The flavor that works for IID data — *random* k-fold — does not work for financial time series, for two independent reasons:

1. **Order matters.** Putting future data in the training set and past data in the test set is look-ahead bias. Random shuffling does exactly this.
2. **Samples are not independent.** A label at time `t` that depends on the next 60 bars of returns overlaps with a label at time `t + 30` that also looks 60 bars forward. Training on one and testing on the other shares information across the split.

Lopez de Prado's *Advances in Financial Machine Learning* (Ch. 7) defines **purged k-fold cross-validation with embargo** as the fix:

- **Purging.** Drop from the training fold any sample whose label-evaluation window overlaps with the test fold. If the test fold covers `[t1, t2]` and you have a training sample at time `s` with horizon `H`, drop it if `[s, s+H]` intersects `[t1, t2]`.
- **Embargo.** After the test fold ends at `t2`, drop training samples in `[t2, t2 + ε]` to absorb serial dependence in residuals. `ε` is typically ~1% of the dataset duration.

**Walk-forward** (a.k.a. expanding-window backtest) is the simpler, blunter cousin: train on `[0, T1]`, test on `[T1, T2]`, then retrain on `[0, T2]`, test on `[T2, T3]`, and so on. It's conservative — you only ever use past data — but it gives you fewer evaluation samples than purged k-fold.

**Combinatorial Purged Cross-Validation (CPCV)** is the higher-power variant: instead of one in-sample/out-of-sample split, you take many disjoint test-fold combinations, evaluate the strategy on each, and aggregate. The result is a distribution of out-of-sample Sharpes, not a single point estimate — which is much harder to over-optimize and naturally pairs with the Deflated Sharpe Ratio (`06-deflated-sharpe.md`).

## When it works

- **You have enough data.** Purged k-fold needs enough samples that purging doesn't gut your training set. For our `signal_outcomes` table at n=272, k=5 with purging leaves around 200 training samples per fold — borderline. n ≥ 1000 is comfortable.
- **You know your label horizon.** Purging requires knowing the maximum bar index touched by each label. With triple-barrier labels (Ch. 03), this is just `t0 + H` (the time-barrier or the actual exit, whichever came first). Cleanly defined.
- **You're going to publish or commit to results.** A model claimed to work on standard k-fold CV will pass internal review and fail in production. Insist on purged + embargo before any go-live decision.

## When it fails

- **Sample size too small to absorb purging.** If purging removes 40% of your training set, your fold-by-fold variance dominates and you're estimating noise. Solution: collect more data, not "use random k-fold anyway." The only thing worse than no validation is fake validation.
- **Label horizon under-stated.** If the actual realized outcome takes longer than the nominal label horizon (e.g. trail-out trades that ran for hours past the stated `H`), purging based on the nominal horizon under-purges and leaks. Always purge by the *actual* `closed_at`, not by the planned `H`.
- **Walk-forward with too few splits.** Three or four expanding-window splits don't carry enough statistical power to distinguish a Sharpe 1.5 strategy from a Sharpe 0.3 one. Use ≥ 10 walk-forward steps or switch to CPCV.
- **Hyperparameter tuning on the wrong CV.** Even if you use purged k-fold for the final evaluation, if you tuned hyperparameters using random k-fold first, the tuning has already leaked. Tuning and final evaluation must use the same (correct) protocol.

## What we do today (in projectr-x)

The current engine **does no cross-validation**, for the same reason it does no supervised ML: it's rule-based, with thresholds set by deliberate judgment and revised on the evidence in `signal_outcomes`. The `deployment_markers` table is the audit trail of those judgments — each row marks an engine change so any subsequent metric query (e.g. `GET /api/signals/metrics?periodDays=30`) can slice "before" vs "after" the change without conflating regimes.

This is not validation in the textbook sense, but it's **honest about what it is**: a live-trade A/B where each `deployment_markers` row is a checkpoint and the next two weeks of `signal_outcomes` rows are the test. There's no train/test confusion because there's no training. The risk is the inverse: you can't tell if a change is an improvement or just regime luck until you have ≥ a quarter of live data with stable thresholds. We don't yet — the v3 → v4 transition was 5 days ago.

Where we will need real CV: when meta-labeling lands (see `02-ml-for-trading.md`). At that point the protocol is:

1. Triple-barrier labels from `signal_outcomes`.
2. Features from the dimension scores + regime + ATR + alignment.
3. Purged k-fold with embargo, with purging on `closed_at` (not `fired_at + H`).
4. Walk-forward on the held-out final 30% of history.
5. Deflated Sharpe required ≥ 1.0 (deflated, not raw) before any production deploy.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/repository/SignalOutcomeRepository.java` — the data store any CV protocol would read from.
- `db/init/signal-init.sql` — `deployment_markers` table; the human-judged "splits" we currently use in place of CV.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/PerformanceMetricsService.java` — the metric slicing that consumes `deployment_markers`.

## Implementation sketch (when ML lands)

The two libraries that already implement the right protocol:

- Python: `mlfinlab` (Hudson & Thames) — has `PurgedKFold`, `CombinatorialPurgedKFold`, and embargo built in.
- Java: no good library. We would re-implement `PurgedKFold` against the `signal_outcomes` table directly. Maybe 1 day of code; the algorithm is short.

The first concrete consumer would be a meta-labeling classifier deciding whether to forward a signal to `trade-execution-service`. Until n ≥ 1000 closed outcomes (likely a quarter from now), this is not actionable — the validation step would be undercut by the small-sample problem above.

## Sources

1. [Lopez de Prado, M. *Advances in Financial Machine Learning*, Chapter 7 "Cross-Validation in Finance"](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086) — the canonical statement of purged k-fold and embargo with full math.
2. [Wikipedia, "Purged cross-validation"](https://en.wikipedia.org/wiki/Purged_cross-validation) — clean concise summary with the procedural pseudocode.
3. [Velazquez Bustamante, A. "KFold cross-validation with purging and embargo" (Medium)](https://antonio-velazquez-bustamante.medium.com/kfold-cross-validation-with-purging-and-embargo-the-ultimate-cross-validation-technique-for-time-2d656ea6f476) — practitioner walkthrough with Python and a worked example.
4. [Quant Beckman, "Combinatorial Purged Cross Validation for Optimization" (with code)](https://www.quantbeckman.com/p/with-code-combinatorial-purged-cross) — CPCV implementation reference.
5. [Insight Big Data, "Traditional Backtesting is Outdated. Use CPCV Instead"](https://www.insightbig.com/post/traditional-backtesting-is-outdated-use-cpcv-instead) — argues the case for CPCV over walk-forward when sample size permits.
6. [ScienceDirect: "Backtest overfitting in the machine learning era"](https://www.sciencedirect.com/science/article/abs/pii/S0950705124011110) — synthetic-controlled comparison of CV methods on finance data, confirms purged variants reduce overfitting materially.
