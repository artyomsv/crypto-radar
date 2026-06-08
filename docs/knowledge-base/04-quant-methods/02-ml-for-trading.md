# Machine Learning for Trading — When It Helps, When It Ruins Your Backtest

> ML's role in trading is narrow and brutal. It helps when the structure is too rich for hand rules; it kills you the moment look-ahead, leakage, or distribution shift sneak in.

## Definition

"ML for trading" covers any supervised, unsupervised, or reinforcement-learned model whose output influences a trade decision: classifying a bar as "imminent breakout vs noise," predicting next-period volatility, scoring an order-book snapshot, ranking symbols, or directly outputting positions. Lopez de Prado's *Advances in Financial Machine Learning* (Wiley, 2018) is the canonical practitioner reference, and his core argument is uncomfortable: **most published ML-for-trading results are statistical artifacts**, and the framework you need to avoid joining them is structurally different from the standard scikit-learn workflow.

Three pieces of that framework are non-negotiable:

1. **Labels must reflect real trade outcomes**, not next-bar returns. That is what the triple-barrier method (`03-triple-barrier-labeling.md`) is for.
2. **Cross-validation must respect time order and account for sample dependence.** Standard k-fold CV leaks. Purged k-fold with embargo doesn't (see `05-overfitting-and-cv.md`).
3. **Performance must be deflated for the number of trials run.** Picking the best of 200 backtests and quoting its Sharpe is fraud against yourself (see `06-deflated-sharpe.md`).

ML is most useful when the relationship between features and outcomes is **non-linear, conditional, and interacted in ways a human would not write down**. Order-book microstructure features, cross-asset funding/OI flows, sentiment-times-volatility interactions — these are good candidates. ML is least useful when the underlying edge is a single linear relationship (then OLS is faster, more interpretable, and less overfit) or when the signal-to-noise ratio is so low that any flexible model will fit the noise (most macro-horizon work).

## When it works

- **Rich feature space, weak hand-engineered priors.** When you have 50+ candidate features and no theoretical reason to prefer one combination over another, a regularized model (Lasso, gradient boosting) can find combinations a human wouldn't.
- **Non-linear interactions known to exist.** Funding × OI direction × spot trend is a textbook example: the joint signal is much stronger than any margin. Tree-based models capture this naturally.
- **Volatility / variance forecasting.** Returns are near-unpredictable; variance is highly persistent and structured. ML models on realized-vol features have a long track record of beating GARCH on out-of-sample log-likelihood.
- **Meta-labeling.** Lopez de Prado's pattern: a hand-built strategy (like our `SignalEngine`) decides direction; an ML model decides whether to take the trade. The ML model never picks a side, only sizes the bet. This is the safest place to apply ML in this repo.

## When it fails

The failure modes below are the entire reason this doc exists. Each one has destroyed real money in real funds.

- **Look-ahead bias.** Any feature computed using information unavailable at decision time. Closing prices computed from bars that include the bar you're predicting. Z-scores that include the current observation. Rolling statistics with `closed='right'` defaults. The fix is mechanical: every feature at time `t` must use data from `t-1` and earlier, full stop.
- **Target leakage.** The label itself contains information that wouldn't be available until after the trade was decided. A "did this position make money in the next hour" label, joined naively to features that were also computed over that hour, is leaked. Triple-barrier labels avoid this by anchoring the label to a forward-looking time window from the entry timestamp, and never reusing that future data as a feature.
- **Sample dependence.** Two overlapping training samples (entry at t=10, exit at t=70; entry at t=30, exit at t=90) share 40 bars of forward returns. Standard CV treats them as independent. They are not. Purging (drop the overlapping training samples from the fold) and embargo (drop bars adjacent to the test window from training) are the fix.
- **Distribution shift.** The model is trained on a market regime that no longer exists. Crypto's structural breaks (post-FTX, ETF approval, halvings) routinely invalidate models built on data older than ~6 months. The remedy is *not* "use more data" — it's regime-conditioned modeling (`MarketRegimeService` is a primitive version of this) and aggressive periodic retraining.
- **Backtest p-hacking.** Run 200 hyperparameter combinations, pick the best Sharpe, report it. The expected best-of-N Sharpe under a true null of zero edge is positive and grows with N. The Deflated Sharpe Ratio (Bailey & Lopez de Prado, 2014) corrects for this and should be the default reporting metric.
- **Cost-blind training.** A model that picks a 0.1% expected move in either direction with 51% accuracy is unprofitable after 0.11% round-trip Bybit fees. The objective function during training must reflect cost reality (see `05-risk-and-execution/04-fees-and-slippage.md`).

## What we do today (in projectr-x)

The current engine is **rule-based, not ML**. That is a deliberate choice given the data we have (~272 closed signals in 14 days as of v4) and the failure modes above. Specifically:

- **No supervised model is fit on `signal_outcomes`.** Every threshold in `SignalEngine` and the detectors is hand-set, version-tagged in `deployment_markers`, and changed by deliberate decision rather than gradient descent.
- **Outcome tracking is set up to support ML when it's ready.** `signal_outcomes` already records every dimension score at fire time (`technical_score`, `whale_score`, …), every excursion (`max_favorable_pct`, `max_adverse_pct`), every fee-adjusted realized R, and the regime at fire time. That schema is intentionally a labeled training set in waiting.
- **Meta-labeling is the most plausible first ML step.** The hand-rules in `SignalEngine` decide BUY/SELL; an ML model trained on dimension scores + regime + ATR conditions could decide *whether to forward the signal to execution* — exactly the meta-labeling pattern. This would slot in front of `trade-execution-service`'s `SignalSubscriber.isBelowAlignmentFloor` check.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeTracker.java` — writes the labeled rows that any future ML model would consume.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java` — the rule-based decision-maker that ML would *complement*, not replace.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/SignalSubscriber.java` — the gate where meta-labeling would attach.

## Implementation sketch (if not implemented yet)

A meta-labeling MVP, in order:

1. Wait for n ≥ 500 closed outcomes per regime (current count ~272 across all regimes; not enough).
2. Train a gradient-boosted classifier on `signal_outcomes` rows with features = the 6 dimension scores + regime + ATR + alignment + strategy, label = `realized_r_multiple > 0` (or > 0.3R for tougher class boundary). Use purged k-fold + embargo (see `05-overfitting-and-cv.md`).
3. Backtest the meta-label on a held-out slice using walk-forward (`07-walk-forward-analysis.md`). Require deflated Sharpe ≥ 1.0 over ≥ 90 trading days before any production deploy.
4. Deploy behind a feature flag that defaults to off. Shadow-log the model's vote next to the human-rule vote for ≥ 2 weeks of live signals before gating execution on it.

Rough effort: 1 week to plumb training data, 1 week to validate, 1 week to integrate behind a flag. Bigger cost is the calendar wait for sample size.

## Sources

1. [Lopez de Prado, M. *Advances in Financial Machine Learning* (Wiley, 2018)](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086) — the textbook. Chapters 3 (labeling), 4 (sample weights), 7 (cross-validation), 8 (feature importance), 12 (backtest pitfalls) are required reading.
2. [Lopez de Prado, M. "The 10 Reasons Most Machine Learning Funds Fail" (GARP white paper, 2018)](https://www.garp.org/hubfs/Whitepapers/a1Z1W0000054x6lUAA.pdf) — short, brutal practitioner summary of the same material.
3. [Bailey & Lopez de Prado, "The Deflated Sharpe Ratio" SSRN 2460551 (2014)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551) — the math for correcting Sharpe under multiple testing. Required reading before publishing any ML result internally.
4. [Quantreo, "Look-Ahead Bias: The Invisible Killer"](https://www.newsletter.quantreo.com/p/look-ahead-bias-the-invisible-killer) — practitioner-facing walkthrough of the most common leakage patterns and how to catch them.
5. [Lopez de Prado *Advances in FML*, Table of Contents (preprint)](https://toc.library.ethz.ch/objects/pdf03/e01_978-1-119-48208-6_01.pdf) — useful for confirming chapter mapping without paywall.
