# Bayesian Updating

> Bayes' theorem says: your posterior belief about a hypothesis equals your prior belief times the likelihood of the evidence, normalized. In trading, the hypothesis is usually "we're still in the trend" or "this signal is real," and the discipline of explicit priors prevents the common error of treating every fresh data point as if you knew nothing before it arrived.

## Definition

### Bayes' theorem

```
P(H | E) = P(E | H) × P(H) / P(E)
```

- `P(H)` — prior probability of hypothesis H before seeing evidence E.
- `P(E | H)` — likelihood: probability of observing E if H is true.
- `P(E)` — total probability of E (the normalizer).
- `P(H | E)` — posterior probability of H given E.

In sequential settings, today's posterior becomes tomorrow's prior. This is the "updating" — beliefs evolve as evidence accumulates, but the rate of evolution is bounded by the prior. A strong prior + weak evidence = small belief change. Weak prior + strong evidence = large belief change.

### Bayesian vs frequentist framing

A frequentist tests "is this signal statistically significant?" against the null hypothesis of randomness. A Bayesian asks "given my prior belief about the signal's edge, how should I update after this trade's outcome?" The frequentist gives a binary verdict per hypothesis test; the Bayesian gives a posterior distribution over edge magnitude.

For trading, Bayesian framing is operationally cleaner because:

1. Decisions are continuous (size up, size down, exit), not binary (accept/reject).
2. Sample sizes are small (you can't afford to wait for 10,000 trades).
3. You always have a prior, even when you pretend not to (your trading-system parameters embed it).

### Sequential updating

The canonical application: estimating the win rate `p` of a strategy from observed trades.

- Prior: `Beta(α, β)` — a flexible distribution over `p ∈ [0,1]`. `α = β = 1` is uninformative (uniform); `α = 10, β = 10` is "I expect 50% win rate, with moderate confidence."
- After observing `w` wins and `l` losses: posterior is `Beta(α + w, β + l)`.
- Posterior mean: `(α + w) / (α + β + w + l)`.

A new strategy with `Beta(2, 2)` prior, after 5 wins and 2 losses: posterior `Beta(7, 4)`, mean = 0.64, 95% credible interval ≈ [0.36, 0.86]. The frequentist would report "win rate 71%, n=7" — losing the uncertainty signal.

### "Still in trend" posterior

A trade is open; price has moved favorably. Question: probability the trend continues vs has ended.

- Prior `P(trend)` from regime classification (e.g. `MarketRegimeService` says BULL → `P(trend) = 0.7`).
- Likelihood: `P(observed price action | trend)` from historical conditional return distributions.
- Posterior `P(trend | observed)` updated each bar.

When posterior drops below a threshold (e.g. 0.4), exit — even if no stop has been hit and no fixed target reached. This is the conceptual underpinning of probabilistic stop-management.

## When it works

- **When priors are informative and stable.** A well-calibrated regime detector provides a meaningful prior; small evidence updates the posterior sensibly.
- **For small-sample edge estimation.** Bayesian credible intervals on a new detector's win rate after 30 trades are more honest than frequentist "n=30, win rate X%" reports.
- **For online updating.** Recursive Bayesian filters (Kalman, particle filters) are natural in streaming environments — each new bar updates the state estimate in O(1).
- **For multi-source evidence.** Different inputs (whale flow, derivatives, technical) update the same posterior; independence assumption is testable.

## When it fails

- **Wrong priors poison everything.** A confident wrong prior with weak evidence stays wrong for many updates. The "I expect 70% win rate" prior on a new strategy hides a real 50% win rate for many trades.
- **Non-independent evidence.** If multiple inputs share a common cause (e.g. funding, OI, and L/S all measure leverage positioning), treating them as independent over-updates the posterior. Naive Bayes on correlated features is a known failure mode.
- **Likelihood mis-specification.** `P(E | H)` is itself a model, usually wrong in tail events. Crypto jumps don't follow the Gaussian likelihood most updating schemes assume.
- **Non-stationarity.** Bayesian updating assumes the parameter being estimated is stable. Strategy edges decay (`techdebt` of every quant fund). Stale data dragging an estimate that should have shifted is a chronic problem.
- **Conjugate-prior tyranny.** Conjugate priors (Beta-Binomial, Normal-Normal, etc.) are mathematically clean but impose specific shapes on the prior. Real priors are often messier — bimodal, fat-tailed — and conjugate updating misrepresents them.

## What we do today (in projectr-x)

We do not currently use explicit Bayesian updating in any service. The system uses:

- **Threshold-based regime classification** (`MarketRegimeService`) — closer to frequentist hypothesis tests on observable conditions.
- **Deterministic scoring** in `SignalEngine` — weighted sum of dimension scores, hard cutoffs for signal labels.
- **Frequentist outcome metrics** (`/api/signals/metrics`) — win rate, avg R, total R over a period. No credible intervals reported on the dashboard.

Where Bayesian updating would fit naturally:

1. **Per-detector edge estimation.** Each detector (`LiquiditySweepDetector`, `TrendContinuationDetector`) has a posterior distribution over its win rate / R-expectancy. As `signal_outcomes` rows close, the posterior updates. Display credible interval on the `PerformanceReport.byStrategy` table instead of point estimates. Concretely: maintain `Beta(α_strategy, β_strategy)` for each detector; persist updates as new closed outcomes arrive.

2. **"Trend continuation probability" in the outcome evaluator.** `OutcomeEvaluator` currently exits on hard rules (target hit, stop hit, trail hit, stagnation). A probabilistic exit — "P(further favorable move) < 0.3 → close" — would be a natural Bayesian extension. The likelihood comes from the conditional return distribution given current MFE state.

3. **Dimension reliability weighting.** Currently the 6 dimensions are equally weighted in the alignment computation. A Bayesian framing would give each dimension a posterior over "predictive accuracy" and weight the alignment by those posteriors. This is roughly what López de Prado's "meta-labeling" does (see `06-ensemble-methods.md`).

The reason we haven't shipped any of this: the threshold/deterministic approach has produced a clean v4 baseline. Switching to Bayesian methods before the deterministic baseline is fully understood would substitute opacity for clarity. Once v4 + v5 outcome data accumulate (target n ≥ 200 per strategy/regime cell), per-detector Bayesian edge estimation is the highest-value first add.

## Sources

1. **Gelman et al., *Bayesian Data Analysis* (3rd ed., 2013).** http://www.stat.columbia.edu/~gelman/book/ — The reference textbook. Chapters 1–4 cover the basics relevant to trading applications. Free online.
2. **MacKay, *Information Theory, Inference, and Learning Algorithms* (2003).** http://www.inference.org.uk/itila/ — Free online. Chapter 2 (probabilities) and Chapter 24 (sequential inference) are the cleanest accessible treatments of updating.
3. **López de Prado, *Advances in Financial Machine Learning* (2018), Chapter 3 (Labeling) and Chapter 4 (Meta-Labeling).** Meta-labeling is a Bayesian-flavored second-pass on primary trading signals.
4. **Sivia, Skilling, *Data Analysis: A Bayesian Tutorial* (2006).** Practical introduction with worked examples; good for translating Bayesian formalism into concrete code.
5. **Robert (2007), *The Bayesian Choice* (2nd ed.).** Theoretical depth on decision-theoretic foundations. Less directly applicable to trading but useful for understanding why Bayesian framing matters for sequential decisions.
6. **Gilks, Richardson, Spiegelhalter (1995), *Markov Chain Monte Carlo in Practice*.** Reference for the computational side — MCMC, the workhorse for non-conjugate posteriors. Relevant when you outgrow conjugate priors.
