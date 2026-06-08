# Monte Carlo Methods

> Monte Carlo simulation generates many possible futures from a model and reports statistics over them. It's the most-useful and most-abused tool in quantitative finance. Used well, it quantifies uncertainty. Used poorly, it manufactures false precision from arbitrary assumptions.

## Definition

Monte Carlo (MC) is a family of techniques for estimating quantities by random sampling. In trading, three flavors matter:

### 1. Bootstrap

**Resample observed historical returns with replacement** to generate alternative histories. No distributional assumption — the empirical distribution does the work.

```
Given: observed daily returns r_1, ..., r_N
For each of K iterations:
    Sample T returns uniformly with replacement from {r_1, ..., r_N}
    Compound to compute total return / max drawdown / Sharpe
Report distribution of statistics across K iterations.
```

Variants:
- **IID bootstrap**: shuffle returns independently. Loses time-series structure.
- **Block bootstrap**: sample contiguous blocks of length b. Preserves short-range autocorrelation and vol clustering.
- **Stationary bootstrap** (Politis & Romano 1994): block length drawn from a geometric distribution. Reduces sensitivity to fixed b.

Use case: estimate the distribution of a strategy's annualized return / drawdown / Sharpe ratio. Returns confidence intervals around point estimates that frequentist closed-form methods cannot easily produce.

### 2. Parametric Monte Carlo

**Fit a distribution to historical data, then sample from the fitted distribution.** Standard examples:

- **Normal**: `r ~ N(μ, σ²)` fit from data. Generates "synthetic returns" that ignore fat tails. Almost always wrong in crypto.
- **Student-t**: `r ~ t(ν, μ, σ²)` with `ν` fit from kurtosis. Captures fat tails better.
- **GARCH-driven**: returns generated from a GARCH(1,1) model with vol clustering. Captures the most-important crypto stylized fact.
- **Jump-diffusion**: returns = diffusion + Poisson jumps. Captures crypto's discrete shocks.

Use case: stress-test a strategy under specific shock scenarios. The output's quality depends entirely on the input distribution's accuracy.

### 3. Geometric Brownian Motion (GBM)

The continuous-time analog: `dS/S = μ dt + σ dW`, where `W` is standard Brownian motion. Discretize:

```
S_{t+Δt} = S_t × exp((μ − 0.5σ²) Δt + σ √Δt × Z),  Z ~ N(0, 1)
```

This is the Black-Scholes data-generating process. GBM is **the** baseline for option pricing and is the default in most introductory MC treatments. It is also **manifestly wrong for crypto** — log-returns are not Gaussian, vol is not constant, jumps are not absent.

Use case: option pricing in well-behaved markets. Educational simulator. Should not be the basis for crypto risk analysis.

## When it works

- **Estimating drawdown distribution.** Bootstrap a strategy's daily PnL series 10,000 times → distribution of max drawdowns. 95th percentile drawdown estimate is a useful risk number that closed-form analytic methods can't produce.
- **Backtest robustness checks.** A strategy with Sharpe 1.5 over a single backtest might have a 95% bootstrap CI of [0.4, 2.6]. That CI is the real story — the point estimate is one realization.
- **Position-size optimization.** Kelly-criterion variants assume a known edge distribution. Bootstrap the edge distribution from historical trades, then optimize position size under uncertainty.
- **Sequential decision evaluation.** Run K simulations of "what if we'd used parameter set X" to compare strategies before live deployment. The variance across K matters as much as the mean.
- **Option pricing on path-dependent payoffs.** Asian options, lookback options, barrier options — closed-form solutions don't exist for most, MC is the standard.

## When it fails

- **Garbage-in, garbage-out.** Parametric MC results inherit all the assumptions of the parametric family. Sampling from a Gaussian when reality is fat-tailed produces drawdown estimates that are systematically optimistic.
- **Bootstrap on non-stationary returns.** Bootstrap assumes the empirical distribution is representative. If the market regime has shifted (post-ETF crypto, post-2022 rate-cycle), recent return distributions don't represent future possibilities.
- **Independence violation in iid bootstrap.** Shuffling returns destroys autocorrelation. A strategy's drawdown statistics depend on autocorrelation. iid-bootstrap drawdowns are smaller than reality.
- **Apparent precision from many samples.** Running 1,000,000 simulations doesn't make a wrong model less wrong. The MC error bar shrinks; the model error doesn't. Reporting "95% CI = [X, Y]" from a misspecified parametric model is precise-looking nonsense.
- **Hidden p-hacking via simulation count.** Running MC, tweaking parameters until the result looks favorable, reporting the favorable run. The same data-snooping problem as backtest-over-fit, just slower to recognize.
- **Survivor bias in historical inputs.** A bootstrap over only the assets still in the universe today understates tail risk — delisted assets had bad tails that aren't in the sample.

## What we do today (in projectr-x)

Nothing. No service runs Monte Carlo simulations. The reasons are deliberate, not incidental:

1. **Outcome evaluation is empirical, not simulated.** `signal_outcomes` accumulates real closed trades; the `PerformanceReport` aggregates real R values. We have no need to simulate when we have N=300+ real trades growing weekly.
2. **No backtest infrastructure.** The project skipped backtesting entirely in favor of running real money (or paper money in demo) against real markets. Without a backtest, there's no surface for bootstrap to operate on.
3. **No option pricing in-house.** `options-service` consumes Bybit's published Greeks and IV; it doesn't price options independently, so the standard MC use cases (American-option pricing, path-dependent payoffs) don't arise.

### Where MC would fit if added

1. **Bootstrap CIs on `PerformanceReport`.** The `/api/signals/metrics?periodDays=30` endpoint reports point estimates of win rate and average R. Adding block-bootstrap-based 95% CIs would be a 1-day implementation and would honestly communicate sample-size uncertainty to anyone reading the dashboard. The block-bootstrap variant is the right choice because consecutive trades share regime context.

2. **Strategy comparison before deployment.** When considering shipping a new detector, bootstrap-resample historical outcomes under the proposed strategy parameters versus current baseline. Report the credible interval on the difference. Lopez de Prado's "Probability of Backtest Overfitting" framework is the directly relevant methodology.

3. **Daily drawdown forecasting for the guardrail policy.** `DailyPnlCalculator` currently flags realized PnL below `-maxDailyLossPercent`. A forward-looking version: simulate the rest of the day's PnL distribution from open positions + recent vol, halt if 95th percentile of forward PnL hits the loss threshold. This would be a useful but specifically-scoped addition.

We deliberately do not plan to add full GBM-based portfolio simulation. Crypto's actual return distribution is sufficiently far from GBM that the output would be misleading-precise. If we ever need parametric simulation, jump-diffusion or GARCH-with-fat-tails is the floor.

## Sources

1. **Glasserman, *Monte Carlo Methods in Financial Engineering* (2003).** https://link.springer.com/book/10.1007/978-0-387-21617-1 — The reference text for financial MC. Chapter 9 (variance reduction) and Chapter 10 (American-option pricing) are practically essential.
2. **Politis, Romano (1994), "The Stationary Bootstrap."** *Journal of the American Statistical Association* 89(428). https://www.jstor.org/stable/2290993 — Foundational paper on the right way to bootstrap time series.
3. **Efron, Tibshirani (1993), *An Introduction to the Bootstrap*.** The original-cohort text on the method. Now a standard reference.
4. **López de Prado (2014), "The Probability of Backtest Overfitting."** *Journal of Computational Finance*. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2326253 — Connects MC simulation to backtest validation; the methodology for avoiding the overfit trap.
5. **Bailey, López de Prado (2014), "The Deflated Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting, and Non-Normality."** *The Journal of Portfolio Management* 40(5). https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551 — Adjustment for inflated Sharpe estimates; uses MC under realistic distributional assumptions.
6. **Joshi, *The Concepts and Practice of Mathematical Finance* (2nd ed., 2008), Chapter 12.** Practical implementation guide for MC in pricing engines.
7. **Andersen, Lund (1997), "Estimating continuous-time stochastic volatility models of the short-term interest rate."** *Journal of Econometrics* 77(2). https://doi.org/10.1016/S0304-4076(96)01819-2 — Methodology for stochastic-vol MC that's closer to crypto's actual data-generating process than GBM.
