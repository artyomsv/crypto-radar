# Time-Series Foundations for Trading Models

> Stationarity, autocorrelation, and the reason every serious model in this repo works on returns — never raw prices.

## Definition

A time series is a sequence of observations ordered by time. In finance the observations are usually a price `P_t` recorded at regular intervals — for projectr-x, 1-minute and 1-day Bybit kline closes. The thing that makes financial time series statistically awkward is that **prices are non-stationary**: the mean and variance drift with time, so the same statistical model fit on 2022 BTC and 2026 BTC would describe two different objects.

A series is **(weakly) stationary** when its mean, variance, and autocovariance structure are time-invariant. Stationarity is the prerequisite for almost every classical estimator — OLS regression coefficients, ARIMA forecasts, GARCH volatility — to converge to anything meaningful. Tsay devotes the entire first chapter of *Analysis of Financial Time Series* to making this point: "Most financial studies involve returns, instead of prices, of assets" because returns are approximately stationary while prices are not.

**Autocorrelation** is the correlation of a series with a lagged copy of itself. For raw prices it is enormous and near 1 at short lags (today's price is almost yesterday's price), which is uninformative. For returns it is small and decays quickly — and the *sign* and *decay rate* of return autocorrelation is exactly what momentum, mean-reversion, and regime-change strategies try to exploit. The autocorrelation function (ACF) and partial autocorrelation function (PACF) are the standard diagnostic tools.

**Log returns** `r_t = ln(P_t / P_{t-1})` are preferred over arithmetic returns `(P_t − P_{t-1}) / P_{t-1}` for three reasons: they are time-additive (`r_{t,t+k} = r_{t,t+1} + r_{t+1,t+2} + … + r_{t+k-1,t+k}`), they are closer to symmetric around zero (which matters for any Gaussian-tailed assumption), and they map cleanly to continuously-compounded growth. For small returns the two are numerically indistinguishable; for the 10–30% daily moves crypto produces during stress, log returns are materially more honest.

## When it works

Stationary-return modeling works when:

- The horizon is short enough that the data-generating process has not changed regimes underneath you. For the 1m candle work the `OutcomeEvaluator` does, a 24-hour window is usually a single regime.
- You compute features over rolling windows rather than the full history. A 30-day rolling z-score is robust to slow drift; a z-score over "all data since 2017" is not.
- You separate the **conditional** mean from the **conditional** variance. Crypto's mean return is near zero and noisy; its conditional variance (realized volatility, ATR) is highly persistent and predictable. Most of the "edge" in short-horizon crypto trading lives in the variance, not the mean.

## When it fails

- **Regime breaks.** A bull-to-bear flip changes the sign of skew and the persistence of variance simultaneously. Any model trained on the prior regime will look broken until you retrain or condition on a regime label. This is exactly why `MarketRegimeService` classifies BTC into BULL/BEAR/CHOP/UNKNOWN before the SignalEngine applies thresholds.
- **Look-ahead in feature construction.** If you z-score against a window that includes the current bar, you've leaked the future into the present. The only safe rolling stat is `[t-N, t-1]`, never `[t-N, t]`.
- **Survivorship in symbol selection.** Backtesting only on coins that still exist today is non-stationary in the worst way — the dead coins are the missing tail. XMRUSDT (delisted by Binance 2024-02-20) is the project's reminder that we have to detect stale klines, not assume the universe is fixed (see techdebt `2-2-silent-delisting-detection-gap`).
- **Heteroskedasticity ignored.** Stationary mean does not mean stationary variance. Crypto variance is heavily clustered (GARCH-style). Treating today's 1m return as if it came from the same distribution as 2am-on-a-Sunday return is the cardinal sin.

## What we do today (in projectr-x)

- **Returns over prices everywhere a statistic is computed.** `SignalEngine` dimensions look at *changes* (% moves, ATR ratios, OI deltas) rather than levels. The exception is the chart layer, which renders raw OHLC — but it never feeds raw OHLC into a model.
- **Rolling windows, never expanding.** `MarketRegimeService` uses the last 60×1d candles with a 50-SMA and 7-day slope. `LiquiditySweepDetector` compares the trigger bar against the prior 3 bars for volume confirmation.
- **ATR-relative thresholds.** `OutcomeEvaluator` uses `MIN_RISK_PCT = 1.5%` as a floor but actual stops are placed via ATR multiples in the detectors (e.g. `STOP_BUFFER_ATR = 0.5` on `LiquiditySweepDetector`). ATR is itself a stationary statistic of recent volatility, so an ATR-multiple stop is regime-adaptive by construction.
- **Regime conditioning.** `SignalEngine.determineSignalLabel` reads the current `MarketRegime` before applying thresholds. BULL raises the SELL bar, BEAR raises the BUY bar — i.e. the engine *refuses to assume the same distribution applies across regimes*.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/MarketRegimeService.java`
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java`
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java`

## Implementation sketch (gaps that remain)

Two things we are not yet doing that the literature would expect:

1. **Explicit stationarity tests.** We never run an ADF or KPSS test on a series before fitting anything to it. For the current rule-based engine this is fine; the moment we introduce an ML model on top of these features (see `02-ml-for-trading.md`), we need a test-the-features step.
2. **GARCH-style conditional variance modeling.** The engine treats volatility as "ATR over the last N bars" — a non-parametric rolling estimate. A GARCH(1,1) fit per symbol would give a forecast of *next-bar* variance, which is materially more useful for stop placement than a backward-looking ATR. Effort: one Python service, or a small Java port, ~3 days. Not on the roadmap yet.

## Sources

1. [Tsay, R. S. *Analysis of Financial Time Series*, 3rd Edition (Wiley, 2010)](https://www.wiley.com/en-gb/Analysis+of+Financial+Time+Series,+3rd+Edition-p-9780470414354) — Chapter 1 lays out why returns are the modeling unit and the formal definition of weak stationarity. Chapter 3 is the GARCH reference.
2. [Tsay textbook PDF (chapter 1 free online)](https://cpb-us-w2.wpmucdn.com/blog.nus.edu.sg/dist/0/6796/files/2017/03/analysis-of-financial-time-series-copy-2ffgm3v.pdf) — direct chapter access for the stationarity / log-return derivations.
3. [Cont, R. "Empirical properties of asset returns: stylized facts and statistical issues" (2001)](https://www.proba.jussieu.fr/pageperso/ramacont/papers/empirical.pdf) — the canonical "stylized facts" paper: heavy tails, volatility clustering, leverage effect, gain/loss asymmetry. Reading this once cures most naïve assumptions about returns being Gaussian.
4. [Hyndman & Athanasopoulos *Forecasting: Principles and Practice* (free online, 3rd ed.)](https://otexts.com/fpp3/) — pragmatic chapters on stationarity, differencing, and ACF/PACF diagnostics with R code.
