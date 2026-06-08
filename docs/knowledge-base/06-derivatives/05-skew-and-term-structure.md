# Skew and Term Structure as Event-Risk Signals

> Term structure (1-day IV vs 30-day IV) and skew (25Δ-put IV vs 25Δ-call IV) compress the option surface into two scalars that change meaningfully on a daily basis. Read together, they tell you what risks the option market is currently paying up to hedge.

## Definition

### Term structure

The **at-the-money term structure** is the curve of ATM IV plotted against time-to-expiry. Three named shapes:

- **Contango** (upward-sloping): far-dated IV > near-dated. Default state in calm markets. Reflects "more uncertainty further out" plus a term premium.
- **Flat**: near-dated ≈ far-dated. Transitional.
- **Backwardation** (downward-sloping): near-dated IV > far-dated. Signals concentrated event risk in the immediate future. A specific FOMC meeting, CPI print, ETF decision, exchange-token unlock.

The slope can be summarized as the ratio `IV_1m / IV_3m` or the difference `IV_3m − IV_1m`. Practitioners watch the **9-day vs 30-day** ATM IV slope on BTC for the same purpose CBOE's VIX/VIX3M ratio serves on SPX.

### Deribit DVOL

Deribit's DVOL is a 30-day forward-looking implied vol index, computed VIX-style from the full BTC option chain. Methodology: a strike-weighted average of OTM option prices, then a square-root annualization. The number is in vol-points (e.g. DVOL = 65 means 65% annualized expected vol over the next 30 days). DVOL has a corresponding 9-day variant. The 9d/30d ratio is the cleanest single read of crypto term structure.

DVOL backwardation (9d > 30d) is rare and historically a leading indicator of large moves over the following 5–15 days — not because vol-traders are clairvoyant, but because backwardation forms when hedgers are paying up for near-dated downside protection in volume.

### Skew

**25-delta risk reversal** = `IV(25Δ-call) − IV(25Δ-put)`. Convention varies; we use the convention where a **negative** RR means puts trade above calls (downside hedging premium = bearish skew).

- **Equity:** SPX RR is persistently negative (puts always richer). A widening from −5 to −10 vol points signals deteriorating sentiment.
- **Crypto:** BTC RR oscillates around zero. Positive (calls > puts) during bull-market FOMO; negative during crash regimes. The sign-flip itself is the signal — most equity practitioners are surprised by how often crypto skew is positive.

The **butterfly** (`(IV(25Δ-call) + IV(25Δ-put))/2 − IV(ATM)`) measures the smile's convexity — how much the wings command over the body. Wider butterflies = more priced-in jump risk on either side.

## When it works

- **Term structure as event clock.** Around scheduled events (CPI, FOMC, ETF decisions, Bitcoin halvings, expected exchange-token unlocks), the front-month IV pumps relative to the back month. Reading the slope tells you when the market believes the resolution will happen.
- **Skew as crowd-positioning meter.** When skew goes deeply negative (puts much richer than calls) while spot drifts down, longs are still active and paying up to hedge. When skew flips positive in a bull run, retail FOMO calls are bidding up the upside wing — historically a 5–10 day lead on tops.
- **Term structure inversion as risk-on/risk-off switch.** SPX VIX backwardation has historically had 10%+ forward 12-month equity returns. Crypto's analog hasn't been formally tested at academic rigor but practitioners observe the same direction.
- **Cross-asset comparison.** When BTC term structure backwardates while SPX VIX backwardates simultaneously, the macro stress signal is much stronger than either alone.

## When it fails

- **Term-structure noise.** Front-month liquidity drops in the final week before expiry — IV readings get unstable as MMs widen quotes. Don't draw conclusions from a 2-day front-month IV that's based on stale screen marks.
- **Listing-day mechanics.** New expiries trade based on dealer inventory before any directional view forms. The first 24h of a new expiry's IV is uninformative.
- **Crypto-specific holiday effects.** No "holidays" in the equity sense, but exchange maintenance windows, regional liquidity drops (Asia-night, US-pre-market) introduce structural intraday vol patterns that wash out the term-structure signal at the hour level.
- **Skew lies about regime.** A flat skew during a quiet uptrend tells you exactly nothing — the absence of crowd-hedging just means nobody's worried, not that nothing will happen.
- **Manipulation.** Single large blocks on illiquid wings move the entire risk-reversal series for hours. Cross-check skew against multiple venues.

## What we do today (in projectr-x)

We do not currently compute skew, butterfly, or term-structure metrics in any service. The data is available (every `OptionSnapshot` has strike + expiry + IV), but no scheduler aggregates them.

The natural integration path:

1. **New scheduler** in `options-service` that, after `OptionsCollectorService` finishes, computes per-`(underlying, snapshot_time)`:
   - `atmIv1d`, `atmIv7d`, `atmIv30d` — interpolated to nearest expiry buckets
   - `termSlope = atmIv30d − atmIv7d` (positive = contango)
   - `rr25d` — 25-delta risk reversal
   - `butterfly25d`
2. **New `surface_metrics` hypertable** alongside `option_opportunities`.
3. **Derivatives-service** ingests the surface_metrics rows alongside funding/OI/L-S to feed the `Derivatives` dimension score.
4. **`SignalEngine`** thresholding: when `termSlope < 0` (backwardation) and recent realized vol is low → boost confidence on long-vol opportunities; when `rr25d` flips sign → flag in the signal raw-data view for analyst review.

The other reason we haven't shipped this yet: pre-v4 `Derivatives` dimension was inverted (see CLAUDE.md "G.1 derivatives unit fix"). Adding more derivatives-side inputs before that fix was validated would have piled noise on top of a known-broken signal. Now that v4 is live and `Derivatives` reads correctly, the surface metrics are a credible next addition.

## Sources

1. **Carr, Wu (2003), "Variance Risk Premiums."** *Review of Financial Studies* 22(3). https://www.jstor.org/stable/30225713 — Decomposes term structure and skew into diffusion vs jump components. Theoretical anchor.
2. **Bennett, *Trading Volatility* (2014).** Chapter 8 (term structure) and Chapter 9 (skew). Free copy: http://www.trading-volatility.com/Trading-Volatility.pdf — Practitioner-readable treatment with worked equity examples that port directly.
3. **Deribit DVOL methodology paper.** https://insights.deribit.com/exchange-updates/dvol-deribit-implied-volatility-index/ — Construction of the BTC 30d DVOL and 9d variant.
4. **Saef, Wang, Aste (2023), "Cryptocurrency-Implied Skew: A Cross-Sectional Study."** https://arxiv.org/abs/2304.06016 — Empirical regime analysis of BTC/ETH skew; documents the sign-flip behavior absent in equity.
5. **Alexander, Heck (2020), "Price Discovery in Bitcoin: The Impact of Unregulated Markets."** *Journal of Financial Stability*. https://doi.org/10.1016/j.jfs.2020.100776 — How Deribit (then unregulated) became the BTC vol price-discovery venue, and why its DVOL leads spot.
6. **CBOE VIX9D and VIX whitepapers.** https://www.cboe.com/tradable_products/vix/ — Reference for the VIX-style aggregation that DVOL imitates.
