# Deflated Sharpe Ratio

> The Sharpe Ratio is biased upward by every backtest you ran and discarded. Bailey & Lopez de Prado's deflation is the correction. For a project that iterates configs as fast as we do, this is non-negotiable before claiming an edge.

## Definition

The classical **Sharpe Ratio** for a strategy with realized returns `R_t` is `SR = E[R] / σ(R)`, scaled to an annual figure by `√T`. It is the most widely cited performance metric in finance. It is also one of the most abused, because it is computed in isolation as if you ran exactly one backtest and reported its result honestly.

You did not. You ran twenty, or two hundred, picked the best, and reported it. The expected value of the best-of-N Sharpe under a true null of *no edge* is positive and grows with N. The "edge" you've found is almost entirely the order statistic of random noise.

The **Deflated Sharpe Ratio (DSR)**, introduced by Bailey & Lopez de Prado (SSRN 2460551, 2014), corrects this:

> "Even at relatively small SR's, the expected maximum SR can be much greater than zero. […] The Deflated Sharpe Ratio (DSR) corrects [the SR] for two leading sources of performance inflation: Non-Normal returns and selection bias under multiple testing."
> — Bailey & Lopez de Prado, *The Deflated Sharpe Ratio*, 2014

The DSR adjusts the realized Sharpe down by:

1. **Higher-moment correction.** Returns are not Gaussian. Negative skew and fat tails make the standard Sharpe overstate quality. DSR plugs the realized skewness `γ_3` and kurtosis `γ_4` into the variance of the Sharpe estimator. Specifically, the standard error of the estimated SR is `√((1 − γ_3 · SR + ((γ_4 − 1)/4) · SR²) / (T − 1))`, which is larger than the IID-Gaussian standard error when skew is negative and kurtosis is high (i.e. always in crypto).
2. **Selection-bias correction.** Given that you ran `N` independent trials, the expected maximum Sharpe under a null of zero-mean skill is approximately `E[max SR] = Z⁻¹(1 − 1/N) · √Var(SR)`, where `Z⁻¹` is the inverse standard normal CDF. The DSR computes the probability that your observed SR exceeds this null maximum, given the trials run.

Output: the DSR is a **probability**, in `[0, 1]`, that the true Sharpe is positive given the realized Sharpe, the number of trials, and the non-normality of the return distribution. A raw Sharpe of 2.0 with skew −1.2, kurtosis 6, and 200 trials might have a DSR of 0.4 — i.e. less than even odds that the edge is real.

## When it works

- **Multi-config iteration is honestly reported.** Every threshold combination tried during development counts as a trial. If you "try one detector, then try 5 different volume thresholds, then 3 stop multipliers, then 4 trail offsets," `N = 1 + 5 + 3 + 4 ≈ 13`. (More precisely, `N` is the number of effectively independent trials; correlated trials count for less. Lopez de Prado's deflation formula uses `N` directly; a stricter variant uses the effective `N` via the trial-correlation matrix.)
- **Returns are skewed and/or fat-tailed.** Always true for crypto. The higher-moment correction matters more here than in equity-fund-of-funds work where DSR was first popularized.
- **Sample size is large enough for the SR estimator to be stable.** Below ~T = 100 observations the DSR's own confidence interval is wide and the correction is dominated by noise. Above T = 1000 it's tight and meaningful.

## When it fails

- **You don't know how many trials you actually ran.** This is the operationally hardest part. Every parameter sweep, every hyperparameter you tried and discarded, every "we tweaked the LS volume ratio from 1.4 to 1.3" counts. The honest practice is to log every variation in a `deployment_markers`-style audit and feed the count into the DSR computation.
- **Returns are computed gross of fees.** A "winning" strategy at a raw Sharpe of 1.5 with 0.11% round-trip cost in 200 trades has often *negative* net Sharpe, and a DSR computed on gross returns is double-counted upward. Cost-realistic returns first, then DSR.
- **Trials are not independent.** If you swept stop-multipliers from 0.4 to 0.6 ATR in 0.01 steps, the 21 trials are almost-perfectly correlated; treating them as 21 independent trials over-deflates. Use the trial-correlation matrix (Lopez de Prado provides the formula) or report a sensitivity range.
- **You report DSR after the model is in production.** The point of DSR is *before-deploy* gating. Computing it post-hoc on a model you're already using is theatre.

## What we do today (in projectr-x)

Brutal honesty: **we do not compute the Deflated Sharpe Ratio anywhere in the codebase**, and the project's iteration history makes that omission painful.

A rough audit of what we've tried since 2026-04-19 (`v1-initial-fixes`):

- v1 → v2: trailing-stop ladder (activation, step, offset) — at least 4 trials in the activation/offset grid.
- v2 → v3: regime detection (BULL/BEAR/CHOP/UNKNOWN) with separate thresholds per regime — at least 12 trials in the threshold matrix.
- v3 → v4: 7 distinct vectors (G.1, G.2, G.3, A, B, D, E, F), each with its own internal calibration. Conservatively 30+ trials.

Total nominal trials over six weeks: **easily 50+**, almost certainly correlated, all on a sample size that has only just crossed 272 closed signals. The 14-day post-v4 result of `+32R total, 2.9% TARGET hit rate, trail does the work` is encouraging — but the raw Sharpe of that result, deflated for the trials we've run, is much less convincing than the +32R headline suggests.

**This is the single most important gap in our analytic methodology.** Every threshold change we've shipped has been justified by an empirical slice of `signal_outcomes`, which is the right instinct. But "this slice looks better than the prior slice" is exactly the selection bias the DSR exists to deflate. We are choosing the best of many trials.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/PerformanceMetricsService.java` — currently computes raw aggregates (winRate, avgR, totalR) but not Sharpe and not DSR.
- `db/init/signal-init.sql` — `deployment_markers` table is the audit-trail-of-trials we'd feed into DSR.

## Implementation sketch

A reasonable first DSR endpoint:

1. Pick a deployment window (e.g. v4 onward).
2. Compute per-signal R-multiples from `signal_outcomes.realized_r_multiple` (already fee-net).
3. Compute Sharpe = mean / std × √(signals-per-year). At ~20 signals/day across 13 symbols, that's ~7300/yr.
4. Compute skew and kurtosis of the R-multiple distribution.
5. Estimate `N` = count of `deployment_markers` rows × average trials-per-version (record this manually as we ship, going forward).
6. Plug into the DSR formula. Report the deflated SR and the implied probability of true positive skill.

Implementation effort: ~1 day for the calculation, ~half a day to plumb into the metrics endpoint. The hard part is the manual trial-count discipline.

Acceptance criterion before any "we have an edge" claim ships externally: **DSR ≥ 0.95** (i.e. ≥ 95% probability the true Sharpe is positive given our trial count).

## Sources

1. [Bailey, D. H., & Lopez de Prado, M. "The Deflated Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting and Non-Normality" SSRN 2460551 (2014)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551) — the primary source; published in *Journal of Portfolio Management* Vol 40, Issue 5, pp 94–107.
2. [Bailey, D. H. — full paper PDF](https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf) — Bailey's own PDF mirror, the simplest way to actually read it.
3. [Lopez de Prado, M. "Deflating the Sharpe Ratio" SSRN 2465675](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2465675) — companion paper, more accessible exposition.
4. [Lopez de Prado *Advances in Financial Machine Learning*, Chapter 11 "The Probability of Backtest Overfitting"](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086) — chapter-length treatment that puts DSR in context with the PBO framework.
5. [Wikipedia, "Deflated Sharpe ratio"](https://en.wikipedia.org/wiki/Deflated_sharpe_ratio) — clean summary of the formula and assumptions.
6. [Bailey et al., "Statistical Overfitting and Backtest Performance" SSRN 2507040](https://sdm.lbl.gov/oapapers/ssrn-id2507040-bailey.pdf) — the PBO companion paper, useful for understanding how selection bias compounds with model complexity.
