# Ensemble Methods

> An ensemble of mediocre signals can systematically beat any single one of them. Stacking, blending, bagging, boosting, and meta-labeling are the techniques for combining weak predictors into strong ones. The trading-specific name for the most useful variant — meta-labeling — comes from López de Prado.

## Definition

### The fundamental result

If you have N predictors with average accuracy `p > 0.5` and errors that are partially uncorrelated, the majority-vote ensemble has accuracy strictly higher than `p` (Condorcet's jury theorem). The catch: it depends on independence of errors. In practice, models trained on the same data with similar architectures have highly correlated errors, and the ensemble gain is modest.

The right framing: ensembles exploit **diverse perspectives on the same problem**. Diversity comes from different feature sets, different architectures, different time-horizons, different data subsets, different loss functions. The less correlated the predictions, the bigger the ensemble gain.

### Canonical ensemble techniques

#### Bagging (Bootstrap Aggregating)

Train K models on K bootstrap samples of the training data. Average their predictions. Reduces variance without increasing bias. Random Forest is bagging applied to decision trees.

Crypto application: bootstrap the training set across regimes, train K models, ensemble their forecasts. The diversity comes from different regime mixes in different bootstrap samples.

#### Boosting

Sequentially train models, each focused on the errors of the previous. AdaBoost, XGBoost, LightGBM are the dominant implementations. Reduces bias more than variance. Tends to overfit if not regularized.

Crypto application: train successive trees on the residuals of a baseline signal model. The boosted ensemble captures non-linearities the baseline misses.

#### Stacking

Train M base models. Train a meta-model that takes the base-model predictions as features and outputs the final prediction. The meta-model learns optimal combination weights, conditional on base-model agreement / disagreement.

Crypto application: dimension scoring (Technical, Whale, Derivatives, Sentiment, OrderBook, Macro) feeding a meta-model that learns the right weights per regime. This is essentially what our `SignalEngine` does, but with hand-tuned rather than learned weights.

#### Blending

Stacking variant where the meta-model is trained on a held-out validation set instead of cross-validated out-of-fold predictions. Simpler, slightly less robust.

### Meta-labeling (López de Prado)

The crypto/finance-specific variant. Two-step process:

1. **Primary model**: a high-recall but low-precision predictor. Generates many candidate trade signals.
2. **Meta-model**: a binary classifier that learns "given the primary's signal, what's the probability the trade is actually a winner?" Trained on labels from the primary's historical performance, with the primary's signal + market context as features.

The meta-model's output is used to **size positions, not generate them**. Signals the primary fires get sized up when the meta-model is confident and sized down (or skipped) when it isn't. This separates the "find candidates" problem from the "filter candidates" problem.

López de Prado's argument: in trading, hit-rate (precision) matters more than recall. A primary model with 55% hit-rate on 100 signals beats a primary model with 51% hit-rate on 1000 signals after costs. Meta-labeling lets you take a high-recall primary and turn it into a high-precision filtered ensemble.

## When it works

- **When base predictors are diverse.** Different time-horizons, different feature sets, different model families. The more orthogonal their errors, the bigger the gain.
- **When over-fitting is the failure mode.** Bagging specifically reduces variance from overfit base learners. Random Forest > single decision tree is the canonical example.
- **For probability calibration.** Stacking with a logistic-regression meta-model produces well-calibrated probabilities — directly usable for position sizing under Kelly criterion or similar.
- **In meta-labeling form, on a noisy primary.** When you have a primary signal that's "right often enough to be interesting but not enough to trade directly," a meta-model that filters the false positives can be transformative.

## When it fails

- **When base predictors share errors.** N copies of the same model don't ensemble usefully. Cargo-cult ensembling (train 50 LSTMs on the same data, average) gains nothing.
- **When the meta-model overfits.** With small samples and rich base-model features, the meta-model memorizes the training distribution. The fix is regularization + out-of-fold training data + honest holdout testing.
- **Computational cost in production.** Running N base models at every signal time costs N× inference. For high-frequency systems this matters.
- **Latency**: ensembles by definition can't be faster than their slowest base model. Real-time systems must account for this.
- **Maintenance burden.** N models means N retraining schedules, N drift checks, N rollback paths. The operational complexity scales with the ensemble size.
- **Hidden dependencies on base models.** A meta-model trained against a specific primary's output distribution silently breaks when the primary is retrained or replaced. The dependency must be explicit.
- **Diminishing returns.** Each additional model contributes less. N=3 typically captures 80% of the ensemble gain that N=20 captures. Past N=5–10, you're paying compute for negligible accuracy improvement.

## What we do today (in projectr-x)

The `SignalEngine` is **structurally an ensemble**, but with hand-tuned weights rather than learned ones. Specifically:

### Stacking-shaped, but rule-based

Six "base predictors" (the dimensions): Technical, Whale, Derivatives, Sentiment, OrderBook, Macro. Each produces a score in `[-100, +100]`. The composite `overall_score` is a weighted sum (currently equal-weighted; the alignment metric measures agreement).

The "meta-model" is `determineSignalLabel(score, alignment, regime)` — a rule-based classifier that maps `(score, alignment, regime)` to one of `STRONG_BUY / BUY / NEUTRAL / SELL / STRONG_SELL`. The regime-dependent thresholds (BULL raises SELL bar, BEAR raises BUY bar) are the regime-conditioning that a learned stacking model would do automatically.

### Meta-labeling-shaped, but rule-based

Two execution-side gates approximate meta-labeling:

- **`SymbolPerformanceGate`**: looks at the last N closed outcomes for the symbol and blocks new signals when cumulative R is too negative. This is meta-labeling in spirit — "the primary fired, but recent history suggests we should skip" — implemented as a rule, not a learned model.
- **`DetectorConfluenceCheck`**: trend-continuation entries require an open `dimension-scoring` outcome on the same symbol+direction in the past 15 minutes. Different detector signals must agree. This is an ensemble unanimity rule.

### Why not learned ensembles yet

1. **Sample size.** Per CLAUDE.md baseline, we have ~35 closed trades pre-v5 and accumulating. Meta-models need 100+ examples per class to train without overfit. We're not there yet for per-symbol or per-regime training.
2. **Interpretability priority.** Phase-2 outcome analysis depended on being able to point at specific scoring decisions and explain them. A learned meta-model would be opaque.
3. **Hand-tuned weights are the current bottleneck.** The dimension equal-weighting is almost-certainly suboptimal — Sentiment is probably much weaker than Technical, for example. But we don't yet have enough outcome data to learn weights reliably. Equal weighting is a deliberate conservative choice until we do.

### Roadmap

When `signal_outcomes` accumulates ≥ 500 closed rows per symbol per regime, the right next step is **a logistic-regression meta-model on top of the existing dimension scores**:

1. Features: 6 dimension scores, alignment, regime label, deployment marker.
2. Target: `realized_r_multiple > 0` (or `> +0.5R` for stricter classification).
3. Training: out-of-fold cross-validation, regularized (L2).
4. Output: posterior probability of trade success.
5. Integration: ship as **shadow output** first — compute the score, log it, don't act on it. After 4+ weeks of comparison against actual outcomes, decide whether to wire it into position sizing or signal gating.

This is exactly the meta-labeling pattern López de Prado describes. The infrastructure to implement it is mostly already there — we just need outcome data.

## Sources

1. **López de Prado, *Advances in Financial Machine Learning* (2018), Chapter 3 (Labeling) and Chapter 4 (Meta-labeling).** The reference for meta-labeling. Required reading for any ensemble work in this codebase.
2. **Breiman (2001), "Random Forests."** *Machine Learning* 45(1). https://link.springer.com/article/10.1023/A:1010933404324 — The bagging-of-trees method that anchors much of modern ML.
3. **Friedman (2001), "Greedy Function Approximation: A Gradient Boosting Machine."** *The Annals of Statistics* 29(5). https://www.jstor.org/stable/2699986 — Original gradient boosting paper. Foundational for XGBoost / LightGBM.
4. **Chen, Guestrin (2016), "XGBoost: A Scalable Tree Boosting System."** *KDD '16*. https://arxiv.org/abs/1603.02754 — The boosted-tree library that dominates ML competitions and many trading applications.
5. **Wolpert (1992), "Stacked Generalization."** *Neural Networks* 5(2). https://doi.org/10.1016/S0893-6080(05)80023-1 — Original stacking paper.
6. **Dietterich (2000), "Ensemble Methods in Machine Learning."** *International Workshop on Multiple Classifier Systems*. https://web.engr.oregonstate.edu/~tgd/publications/mcs-ensembles.pdf — Canonical survey covering bagging, boosting, stacking, and the diversity-vs-accuracy trade-off.
7. **Lopez de Prado, "The 10 reasons most machine learning funds fail" (2018).** *Journal of Portfolio Management*. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3104816 — Practitioner-facing discussion of why ML approaches underperform in trading; meta-labeling is reason 3-4's antidote.
