# Feature Engineering for Trading Models

> Features are where edges live and where naive practitioners die. The rules: no future leakage, no redundancy, no fixed lookbacks across regimes.

## Definition

A **feature** is any number computed from past market data that is intended to be predictive of a future outcome. The full set of features feeding a model is the **feature space**. Feature engineering is the practice of constructing, transforming, scaling, and pruning that space so that a downstream model — whether a hand-set rule or a fitted classifier — has the best chance of finding signal without overfitting noise.

Five operations matter for short-horizon crypto:

1. **Rolling z-scores.** `z_t = (x_t − μ_{t-N:t-1}) / σ_{t-N:t-1}`. Strips the level and the drift; expresses every observation in standard deviations of recent context. The closing window must be `[t-N, t-1]`, never include `t`.
2. **Differencing.** First differences (returns), log-differences (log returns), and fractional differencing (Lopez de Prado, Ch. 5) — the latter preserves more memory than first-differencing while still achieving stationarity.
3. **Vol-scaling.** Divide moves by ATR or realized vol to compare across regimes. A 1% move in a calm tape is information; a 1% move in a 6% ATR day is noise.
4. **Encoding regime / category.** Categorical context (regime, day-of-week, funding sign) belongs in the feature space as one-hot or embedding columns, not as silent global state.
5. **Lookback selection.** Window lengths drawn from an exponential set (`5, 10, 20, 40, 80, 160`) rather than linear — linear oversamples high values and increases overfitting risk (per The Alpha Scientist's feature-engineering write-up).

## When it works

- **The feature distribution is comparable across the train/test window.** Z-scoring or vol-scaling generally fixes this. Raw price levels never are.
- **Each feature carries information the others don't.** Pairwise correlation < ~0.7, variance-inflation-factor (VIF) < 10 across the space. Beyond that, you're feeding the model redundant noise — at best harmless, at worst it inflates importance scores for whichever variant the model latched onto and destroys interpretability.
- **The lookback matches the horizon.** Features with a 5-bar window predict 5–10-bar horizons. Mixing 30-day features into a 1-minute prediction is feasible but rarely additive — the long lookback's information is dominated by faster features.

## When it fails

- **Look-ahead in the feature itself.** The most common failure. Pandas' `rolling(N)` includes the current bar by default; if you `shift(1)` after computing, you're fine, but the shift is silent if you forget. Same trap exists in any custom-windowed Java code (`OutcomeEvaluator` uses `[t-N, t-1]` explicitly to avoid this).
- **Survivorship bias in training data.** Features computed on a delisted coin's last 30 days are valid; the bug is excluding the delisted coins from your training set. See the XMRUSDT incident.
- **Multicollinearity.** Two features that measure the same thing (RSI(14) and RSI(21) for example) inflate each other in linear models and split importance arbitrarily in tree models. Either drop one or combine into a principal component.
- **Composite scores that drown signal in noise.** This is exactly what bit us pre-v4. The `SignalEngine` 6-dimension composite (technical + whale + derivatives + sentiment + orderbook + macro) was meant to be the prediction signal. In the empirical slice it wasn't: the *individual* whale dimension separated winners from losers by ~16.7 points, while the *composite overall_score* separated them by only 2.2. Averaging the six dimensions destroyed the strong univariate signal in the whale column by mixing it with four near-zero dimensions and one inverted-sign one (the broken derivatives scorer fixed in G.1).
- **Fixed lookbacks across regimes.** A 14-bar ATR is fine; a 14-bar mean is highly regime-dependent and what was "extended" in a low-vol week is "average" in a high-vol week. Vol-normalize before applying any threshold.

## What we do today (in projectr-x)

The current `SignalEngine` is a hand-engineered linear combiner over six dimensions:

- `technical_score` — from `analytics-service`, derived from indicator cluster (RSI, MA cross, MACD, ADX).
- `whale_score` — from `whale-service`, derived from 6-exchange WebSocket flow detection.
- `derivatives_score` — funding rate Z, OI delta, long/short ratio. Fixed in G.1 (v4) after a sign-error caused it to vote the wrong way on every short setup.
- `sentiment_score` — news/RSS sentiment, plus the G.2 fix that unblocked the trading-pair sentiment feed.
- `orderbook_score` — top-of-book imbalance + depth concentration. The G.3 fix corrected the dimension name lookup that was leaving the column NULL pre-v4.
- `macro_score` — BTC dominance, USDT mcap, total3 trends.

The `overall_score` is the alignment-weighted average. The 14-day empirical finding that `overall_score` separated winners from losers by only 2.2 (vs whale's 16.7) is the single biggest lesson in this doc: **uncritical averaging is feature destruction**.

Each dimension is itself a small feature-engineering problem. The detectors `LiquiditySweepDetector` and `TrendContinuationDetector` use a different (and arguably better) approach: a handful of explicit ATR-relative thresholds rather than a single combined score:

- `MIN_PIERCE_ATR_FRACTION = 0.3`
- `MIN_RECLAIM_BODY_RATIO = 0.3`
- `MIN_ATR_PCT = 0.003`
- `MIN_VOLUME_RATIO = 1.3` (against prior 3 bars — explicit `[t-3, t-1]` lookback, no leakage)
- `STOP_BUFFER_ATR = 0.5`

This is closer to what a fitted model would output as feature importances after pruning.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java` — the 6-dimension composite. The whole reason this doc exists.
- `services/signal-service/src/main/java/com/cryptoradar/signal/detector/LiquiditySweepDetector.java` — example of explicit, ATR-scaled, individually-thresholded features.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeTracker.java` — persists the dimension snapshot, which is the labeled feature record any future ML model would consume.

## Implementation sketch (what to fix next)

1. **Replace the composite-score gating with per-dimension thresholds.** Hypothesis: requiring whale ≥ +15 AND technical ≥ +10 will beat requiring overall ≥ +25. Effort: 2 days plus a 2-week observation window before drawing conclusions.
2. **Add fractional differencing** (Lopez de Prado Ch. 5) for the slow-moving dimensions (macro, sentiment) where first-differencing throws away too much memory. Effort: 3 days.
3. **Compute and log VIFs** across the 6 dimensions on the full `signal_outcomes` history. If two dimensions are collinear (likely: whale and orderbook both measure flow imbalance), promote one and demote the other. Effort: half a day.
4. **Stop using a fixed regime-agnostic dimension weighting.** The alignment-weighted average can be regime-conditioned the same way `determineSignalLabel` already is — different weights in BULL vs BEAR vs CHOP. Effort: 1–2 days plus a tuning loop.

## Sources

1. [Lopez de Prado, M. *Advances in Financial Machine Learning*, Chapter 5 "Fractionally Differentiated Features"](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086) — the canonical chapter on preserving memory while achieving stationarity.
2. [The Alpha Scientist, "Stock Prediction with ML: Feature Engineering"](https://alphascientist.com/feature_engineering.html) — practitioner-facing guide on rolling stats, exponential-spaced lookbacks, and the exact failure modes covered above.
3. [Arxiv 2303.16117 "Feature Engineering Methods on Multivariate Time-Series Data for Financial Data Science Competitions"](https://arxiv.org/pdf/2303.16117) — survey of techniques actually used by competition winners.
4. [DotData, "Practical Guide for Feature Engineering of Time Series Data"](https://dotdata.com/blog/practical-guide-for-feature-engineering-of-time-series-data/) — clean treatment of differencing, lag features, and aggregations with the time-leakage caveats called out explicitly.
5. [QuantAlgo, "Rolling Z-Score Trend"](https://www.tradingview.com/script/rbeErVR5-Rolling-Z-Score-Trend-QuantAlgo/) — practical implementation of rolling z-scoring on price data with the right window mechanics.
