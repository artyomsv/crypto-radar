# Implied vs Realized Volatility

> Implied volatility (IV) is the market's forecast of future return-stdev priced into options. Realized volatility (RV) is what actually happened. Their persistent gap is the volatility risk premium — and where it inverts is where vol-buying strategies have positive expectancy.

## Definition

**Implied volatility** is the value of `σ` that, plugged into an option pricing model (Black–Scholes for European, binomial trees for American), produces the option's observed market price. It is a model-dependent forecast, not an observable. Different models, different IVs from the same price. ATM near-expiry IV is the most informative single number — it's where vega sensitivity peaks and where the bid-ask is tightest.

Implied vol is conventionally quoted annualized: an option with IV=80% implies a one-standard-deviation move of `80% / √(252) ≈ 5%` over one trading day. For crypto, the convention uses `√365` because crypto trades 24/7/365 — see the formula in `RealizedVolService.computeAnnualized`. Deribit publishes a single composite IV per maturity called the **DVOL**, modeled on the CBOE's VIX construction.

**Realized volatility** is the standard deviation of past returns, annualized. Multiple estimators exist, ordered by sample efficiency (lower variance for the same window length):

- **Close-to-close (CC):** `σ_CC = stdev(ln(C_t / C_{t-1})) × √365`. Uses only daily closes. Simple, unbiased, ignores intraday range. Variance is ~10× higher than range-based estimators for the same sample. This is what our `RealizedVolService` computes today.
- **Parkinson (1980):** uses daily high–low range. `σ_P² = 1/(4 ln 2) × mean(ln(H_t / L_t)²)`. ~5× more efficient than CC. Underestimates vol because it ignores opening jumps.
- **Garman–Klass (1980):** combines open, high, low, close: `σ_GK² = 0.5 × mean(ln(H/L)²) − (2 ln 2 − 1) × mean(ln(C/O)²)`. ~7× more efficient than CC. Still ignores overnight gaps (irrelevant for 24/7 crypto).
- **Yang–Zhang (2000):** weights overnight return, open-to-close, and Rogers–Satchell range. Unbiased in the presence of opening jumps, drift-independent, ~14× more efficient than CC. The state of the art for daily-frequency RV. https://www.jstor.org/stable/3216080

For continuously-traded crypto, the gap between CC and Yang–Zhang narrows because there are no opening jumps in the traditional sense — but listing events, exchange halts, and sub-minute flash moves still create discontinuities that close-to-close misses entirely.

## The volatility risk premium (VRP)

Empirically, on long-dated equity indices, IV exceeds subsequent realized vol on average. This is the **volatility risk premium**: option sellers are compensated for taking on tail risk. Bennett (*Trading Volatility*, 2014) puts the SPX VRP at ~4 vol points annualized over rolling 30-day windows.

In crypto, the VRP exists but is smaller, noisier, and **frequently inverts**. Deribit ATM 30-day IV trades below 14-day realized vol roughly 25–35% of the time in calm regimes — meaning options are cheap relative to what the market has actually done. That's the regime our `OpportunityScorer` is built to detect.

## When it works

- **Selling vol (short VRP):** In equity index, vol-selling has historically delivered Sharpe ~0.8 (1990–2020) with catastrophic 2008/2020-style tail losses. In crypto, the trade is much harder — VRP is unstable, tail events are larger and more frequent.
- **Buying vol (long IV when RV > IV):** When recent RV materially exceeds forward IV and a continuation catalyst is plausible, long straddles/strangles have positive expected value. Bennett's chapter 6 quantifies this for equities; the same logic applies to crypto with bigger error bars.
- **Vol forecasting:** RV has strong autocorrelation (vol clustering, GARCH effect). Today's RV is the best naive forecast of tomorrow's RV; combining it with IV through a HAR-RV or HEAVY model beats either alone.

## When it fails

- **Regime changes:** RV computed over the past 7d gives a confidently wrong answer the day after a 20% move. Mean reversion of vol means yesterday's calm is no guarantee of tomorrow's.
- **Small-cap manipulation:** RV on illiquid alts is dominated by stale ticks and one-shot wicks. Filter `MIN_ATR_PCT = 0.003` is the analog we apply in `LiquiditySweepDetector` for the same reason.
- **Forward-looking selection bias:** Comparing IV today to RV that hasn't happened yet is the right comparison; comparing IV today to backward-looking RV is convenient but tells you about the past, not the future. The honest version waits for the realization period to complete.
- **Cross-venue arbitrage:** Bybit IV ≠ Deribit IV for the "same" contract because settlement, margin, and inventory differ. Don't compare IV from venue A to RV from venue B without acknowledging the basis.

## What we do today (in projectr-x)

The full path:

1. **`RealizedVolService.computeAnnualized(underlying, lookbackDays)`** — close-to-close estimator over `lookbackDays + 1` daily closes from the shared `candles` hypertable. Returns annualized percent. We deliberately do NOT use Bybit's published HV column for the gap score — using their internal HV as input to a "Bybit IV vs HV" comparison would tautologically reflect their own engine's choices. Same data, our own math.
   - File: `services/options-service/src/main/java/com/cryptoradar/options/service/RealizedVolService.java`
   - Default lookback: 14d (matches the `realized_vol_14d` opportunity field) and 7d (matches `realized_vol_7d`).

2. **IV ingestion** — `OptionsCollectorService` pulls Bybit's chain. Per-contract `markIv` is stored on `OptionSnapshot.impliedVol`. ATM IV for the opportunity is the average of the nearest call and put IV (see `OpportunityScorer.pickStrangle`).

3. **IV-vs-RV gap score** — `OpportunityScorer.computeGapScore(iv, rv)` returns `clamp((rv − iv) / rv × 100, 0, 100)`. Asymmetric by design: when IV > RV (options expensive), score is zero — we don't sell vol from this service. Only the positive tail (RV > IV) generates opportunities.

4. **Composite confidence** — `0.6 × ivRvGapScore + 0.4 × signalOverlay` (`SignalOverlayService.score`). The overlay reads recent signal density, average alignment, and recent abs(R) on the underlying from `signal_outcomes`. The intent: high signal activity + cheap IV = upcoming move + cheap optionality. Threshold for persistence: `confidence ≥ 70`.

5. **Dedup** — `option_opportunities` rows are suppressed for 60 minutes per `(callSymbol, putSymbol)` to prevent the 60s scheduler from spamming near-identical cards.

## Sources

1. **Bennett, *Trading Volatility, Correlation, Term Structure and Skew* (2014).** http://www.trading-volatility.com/Trading-Volatility.pdf — Free book, chapters 3 (IV vs RV concepts) and 5 (estimators). Cited in our OpportunityScorer design.
2. **Yang, Zhang (2000), "Drift-Independent Volatility Estimation Based on High, Low, Open, and Close Prices."** *The Journal of Business* 73(3). https://www.jstor.org/stable/3216080 — Defines the YZ estimator; benchmark for any realized-vol upgrade beyond close-to-close.
3. **Parkinson (1980), "The Extreme Value Method for Estimating the Variance of the Rate of Return."** *The Journal of Business* 53(1). https://www.jstor.org/stable/2352357 — Original high-low range estimator.
4. **Carr, Wu (2009), "Variance Risk Premiums."** *Review of Financial Studies* 22(3). https://www.jstor.org/stable/30225713 — Quantifies VRP across equity indices and individual names; method directly portable to crypto.
5. **Deribit DVOL methodology.** https://insights.deribit.com/exchange-updates/dvol-deribit-implied-volatility-index/ — How Deribit constructs a VIX-like composite from the BTC option chain.
6. **Bybit V5 API — Get Historical Volatility.** https://bybit-exchange.github.io/docs/v5/market/iv — Bybit's published HV column. Returned by `RealizedVolService.fetchBybitHv` for cross-check (not used in scoring).
