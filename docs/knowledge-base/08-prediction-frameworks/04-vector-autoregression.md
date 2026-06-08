# Vector Autoregression (VAR)

> VAR models a system of interrelated time series, with each variable a linear function of its own lags and the lags of every other variable in the system. It's the standard econometric tool for multi-asset prediction. In crypto it works tolerably in calm regimes and fails catastrophically at regime breaks — which is when you most want it to work.

## Definition

A standard VAR(p) model on K time series stacked into a vector `y_t`:

```
y_t = c + A_1 y_{t-1} + A_2 y_{t-2} + ... + A_p y_{t-p} + ε_t
```

where each `A_i` is a `K × K` matrix of coefficients, `c` is a `K × 1` constant, and `ε_t` is white noise with covariance matrix `Σ`. For `p = 1` and `K = 3` (say BTC, ETH, SOL returns), the model is 9 lag coefficients + 3 constants + 6 unique covariance terms.

Parameters estimated via OLS equation-by-equation (consistent because each equation has the same right-hand-side variables) or via maximum likelihood.

### What VAR captures

- **Own-lag dynamics**: BTC return at `t` depends on BTC return at `t-1`, `t-2`, ...
- **Cross-lag dependence**: BTC return at `t` depends on ETH return at `t-1`. This is the value-add over univariate AR.
- **Contemporaneous correlation**: captured by `Σ`, the residual covariance matrix.

### What you do with a fitted VAR

- **Forecast**: iterate forward to predict `y_{t+h}`.
- **Impulse response**: shock one variable, trace the effect on all other variables forward in time. The standard tool for "if BTC drops 5%, what happens to ETH over the next 5 bars?"
- **Granger causality**: test whether past values of variable X help predict variable Y, beyond Y's own lags. The standard test for "does whale flow predict price?" or "does funding predict returns?"
- **Variance decomposition**: at horizon `h`, what fraction of variable Y's forecast variance is attributable to shocks in X?

### Why VAR is the standard tool

- **Theoretically grounded**: well-developed asymptotic theory, plenty of textbook treatment.
- **Computationally cheap**: OLS estimation, closed-form forecasts.
- **Interpretable**: the impulse-response function plot is the cleanest "what causes what" visual in econometrics.

## When it works

- **Slowly-moving macro variables**: GDP growth, inflation, FX rates. Multi-decade history, stable regimes, clear causal stories.
- **Short-horizon cross-asset prediction in calm regimes**: an hour-ahead BTC forecast that uses ETH and SOL lag returns will modestly beat a univariate forecast in calm conditions.
- **Causality testing as exploratory tool**: Granger-causality between funding rates and returns, between whale flow and order-book imbalance, etc. The output is "does this contain predictive information" rather than "is this profitable to trade."
- **Variance attribution for portfolio risk**: how much of portfolio variance comes from a common factor vs idiosyncratic. Useful for risk-management even when forecasting itself doesn't work.

## When it fails

- **Regime breaks.** VAR assumes the coefficient matrices `A_i` are stable through time. They are not. A VAR fit on 2023 data predicts terribly across the 2024 ETF launch. Crypto has had at least three major structural breaks since 2017.
- **Non-linearity.** VAR is linear by construction. Crypto features sharp non-linearities — liquidation cascades, flow asymmetry between fear and greed regimes — that no linear model captures.
- **Heavy-tailed residuals.** OLS coefficient estimates are unbiased even with non-Gaussian errors, but forecast intervals and Granger-causality F-tests assume Gaussian residuals. Crypto residuals are not Gaussian. P-values are biased.
- **Curse of dimensionality.** A VAR(4) with K=10 assets has 400 lag coefficients. Estimation requires far more data than crypto's short history reliably provides. Bayesian VARs with shrinkage priors (Minnesota prior, BVAR) help; they don't solve the regime-break problem.
- **In-sample fit ≠ out-of-sample forecast.** Standard econometrics warning. Apparent R² > 0.5 in-sample becomes R² < 0.05 out-of-sample reliably.
- **Cointegration handling.** Two non-stationary series can have a stationary linear combination (cointegration). Naive VAR on non-stationary levels misses this. The right tool is VECM (vector error-correction model). For crypto, where many series share a common BTC factor but aren't truly cointegrated in a Johansen sense, VECM is also a stretch.
- **Granger-causality is not causality.** "X Granger-causes Y" means past X helps predict future Y. It does not mean X causally drives Y. Both could be driven by an unobserved third factor (Z). Treat Granger results as exploratory, not causal.

## What we do today (in projectr-x)

Nothing direct. No service fits a VAR model. The dimension scoring stack in `SignalEngine` is closer to a linear additive model (sum of dimension scores) than a VAR — there's no explicit lag structure, no cross-variable dynamics. Each dimension reads its inputs at time `t` and contributes to the score at time `t`.

The closest existing analog: `MarketRegimeService` reads BTC alone but produces a label that modulates signal thresholds across the 13-pair universe. This is an implicit cross-asset linkage ("BTC regime drives alt threshold") but it's a one-direction static rule, not a fitted VAR.

### Implementation sketch (if added)

If VAR were added to the project, the natural form is **small-K, low-p, regime-conditional**:

1. **Variables**: `(BTC_return, ETH_return, BTC_funding_rate_change, BTC_OI_change, dominant_whale_flow_delta)` — 5 series, capturing price and the leading flow indicators.
2. **Lag order**: `p = 4` for 1-minute bars (looks back 4 minutes). Information-criterion selection from `p ∈ {1..8}`.
3. **Estimation**: rolling 7-day window, refit hourly. Per-regime estimation: one VAR for BULL, one for BEAR, one for CHOP.
4. **Output**: 5-minute-ahead forecast for `BTC_return`. Compare forecast against realized; use as input to a probabilistic confidence score on signals.

What we'd use it for: **a probability boost on signals that align with the VAR forecast**, and a tax on signals that contradict it. Not as a primary signal — the false-positive rate is too high.

### Why we haven't built it

Three reasons:

1. **Data sparsity per regime.** Three regime labels × 7-day rolling window means ~6,000 minute-bars per regime, ~1,000 in CHOP-with-recent-fit. With 5 variables and lag 4, that's 100 coefficients per regime per fit — borderline estimable. We'd need more variables or shorter lags to hit the threshold.
2. **Better methods exist.** Random forest / gradient boosting fits the same kind of cross-asset relationship without VAR's linearity / stationarity assumptions. If we were going to spend infrastructure on cross-asset prediction, that's the direction.
3. **Lower hanging fruit.** Outcome-driven feedback (per-detector edge estimation, per-symbol gating from `SymbolPerformanceGate`) generates more incremental edge per engineering hour than fitting and maintaining a VAR system.

VAR remains in our toolbox as a **diagnostic tool for ad-hoc analysis** — running Granger-causality between whale flow and 5-minute returns to validate that our `Whale` dimension contains predictive information, for example. We use the `statsmodels` Python library (outside the production services) for these analyses; it's not in the production critical path.

## Sources

1. **Sims (1980), "Macroeconomics and Reality."** *Econometrica* 48(1). https://www.jstor.org/stable/1912017 — The foundational VAR paper. Nobel-winning argument that "structural" models impose untestable identifying restrictions; VAR is the agnostic alternative.
2. **Lütkepohl, *New Introduction to Multiple Time Series Analysis* (2005).** The standard textbook. Chapter 4 (estimation), Chapter 6 (impulse response), Chapter 7 (variance decomposition), Chapter 8 (forecasting).
3. **Hamilton, *Time Series Analysis* (1994), Chapter 11.** Alternative textbook treatment. Excellent on the link between VAR and structural modeling.
4. **Granger (1969), "Investigating Causal Relations by Econometric Models and Cross-Spectral Methods."** *Econometrica* 37(3). https://www.jstor.org/stable/1912791 — Definition of Granger causality.
5. **Bańbura, Giannone, Reichlin (2010), "Large Bayesian vector auto regressions."** *Journal of Applied Econometrics* 25(1). https://doi.org/10.1002/jae.1137 — Shrinkage / Bayesian extensions for high-dimensional VARs; the practical workaround for the curse of dimensionality.
6. **Caporale et al. (2020), "The Causality between Cryptocurrencies and Macroeconomic Variables: A VECM Approach."** *Research in International Business and Finance* 51. https://doi.org/10.1016/j.ribaf.2019.101144 — A real-world crypto VAR application. Findings: weak evidence of macro→crypto causality, mostly within-crypto dynamics dominate.
7. **Bouri, Lucey, Roubaud (2020), "The volatility surprise of leading cryptocurrencies: Transitory and permanent effects."** *International Review of Financial Analysis* 70. https://doi.org/10.1016/j.irfa.2018.10.008 — Application of VAR-style decomposition to crypto vol shocks; useful methodology reference.
