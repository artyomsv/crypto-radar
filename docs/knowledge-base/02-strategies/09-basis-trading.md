# Basis Trading

> The spot-perp basis is a real-time barometer of leveraged positioning. Beyond the cash-and-carry arbitrage opportunity, the basis itself is information — a directional sentiment indicator with documented predictive power.

## Definition

The "basis" in a perpetual futures context is the difference between the perp price and the spot price for the same asset, usually expressed as a percentage of spot:

```
basis_pct = (perp_price − spot_price) / spot_price × 100
```

A positive basis means perp trades above spot — the market is paying a premium to be long-leveraged. A negative basis means perp trades below spot — the market is paying to be short-leveraged. Through the funding rate mechanism (see `08-funding-rate-arbitrage.md`), this premium gets converted to recurring cash flow that pulls the perp price back toward spot. But the *raw basis* before the funding settlement is itself a real-time read on aggregate leveraged positioning.

There are two distinct strategies that use the basis:

1. **Basis as an arbitrage opportunity** — cash-and-carry, documented in `08-funding-rate-arbitrage.md`. The trader takes both legs of the spot-perp pair and harvests the convergence.
2. **Basis as a sentiment indicator** — the focus of *this* document. The trader takes a *directional* position informed by what the basis reveals about crowding. Empirically, an extreme positive basis tends to mark short-term tops; an extreme negative basis tends to mark short-term bottoms. The thesis: when leverage is most crowded on one side, it's mechanically vulnerable to a cascade in the other direction.

The classical economics frame is **Keynes's normal backwardation** (1930) — futures prices tend to trade below expected future spot because producers pay a premium to hedge. In crypto, the relationship is inverted: speculators dominate the perp market and pay producers (via positive funding) to obtain leverage. A persistently positive basis is therefore evidence of speculator demand for leverage, which is a contrarian signal at extremes.

## When it works

- **Sentiment extremes mark turning points.** When BTC perp basis vs Coinbase spot exceeded +1.5% for sustained periods, historical episodes (Apr 2021, Oct 2021, Mar 2024) coincided with short-term tops within days. When basis went to -1.0% or worse (Mar 2020, Jun 2022, Nov 2022), bounces followed within hours-to-days.
- **Forced unwinding cascades.** A heavily positive basis with high funding compounds — longs that don't close pay incrementally, building pressure to close. When the unwinding starts, the basis snaps negative quickly. Catching the cascade direction is profitable.
- **Cross-venue basis dispersion.** When Bybit perp basis is +0.8% but Binance is +0.3% on the same asset, there's an arbitrage opportunity (more relevant to the cash-and-carry strategy) and a *sentiment* read — Bybit has retail leverage piling on disproportionately, suggesting Bybit-specific liquidations may trigger first.
- **As a confirmation overlay on directional signals.** A liquidity-sweep SELL signal (see `03-liquidity-sweep-and-reversal.md`) with extreme positive basis is higher-confidence than the same signal in a neutral-basis regime. Crowding is the necessary precondition for the unwind that the sweep tries to capture.
- **Bitfinex margin lending basis.** A historical specialty: Bitfinex publishes a 24h margin-lending rate and an order-book swap rate that, taken together, function as an alternate "basis" signal. Bitfinex basis research from 2017-2020 documents a number of contrarian setups.

## When it fails

- **Sustained positive basis in genuine bull markets.** During major macro phases — Q4 2020, Q1 2021, Q4 2024 — perp basis stayed positive at 0.5-1.5% for weeks. A contrarian-short strategy on "extreme positive basis" would have died early in each phase. The basis was reflecting genuine institutional demand, not a vulnerable retail crowd.
- **Funding-rate clamps mute the signal.** Bybit and Binance cap funding at ±0.05% per 8h period (with adjustments). When the underlying "true" basis demand would push funding higher, the clamp prevents it; the basis stays wider than usual, but the funding-driven convergence is constrained. The signal-to-noise drops in those regimes.
- **Spot venue mismatch.** Bybit perp vs Bybit spot is a tight basis. Bybit perp vs Coinbase spot can have large persistent gaps due to KYC, geography, and stablecoin-vs-USD pricing — and that gap *is not arbitragable* by most traders. Reading "Bybit perp − Coinbase spot" as a sentiment signal requires understanding which venue's flow dominates.
- **Stablecoin de-peg confusion.** USDT-denominated perp vs USD-denominated spot can show large basis purely from USDT trading off its peg. The basis says nothing about positioning; it says everything about USDT redemption fear.
- **Stale or laggy spot data.** Spot exchanges have lower tick rates than perp markets. A sudden perp move appears as basis dispersion before the spot prints update — the basis spike is a measurement artefact, not a real signal.
- **Crowded contrarian trades.** Once "basis-as-sentiment" becomes a known strategy (it is), the contrarian crowd itself becomes the new majority. Late-2024 saw multiple high-basis events where the expected reversion didn't happen because too many traders were already positioned for it.

## What we do today (in projectr-x)

We do **not** currently compute or persist a spot-perp basis time-series. Our data architecture has both inputs available — `market-data-service` collects Binance spot candles, `derivatives-service` collects Bybit perp markers — but no service computes the basis explicitly.

The closest existing feature: **funding rates** are collected and fed into the Derivatives dimension scorer in `signal-service`, where persistent positive funding skews the dimension toward SELL (consistent with the contrarian-basis thesis). This captures the *funding-rate proxy* for basis but not the raw basis itself, which has higher resolution.

Files involved:

- `services/derivatives-service/src/main/java/com/cryptoradar/derivatives/service/DerivativesService.java::refreshFundingRates` — pulls Binance perpetual funding rates every 5 min.
- `services/market-data-service/src/main/java/com/cryptoradar/marketdata/service/MarketDataService.java` — fetches spot prices for the same symbols.
- The Derivatives dimension inside `services/signal-service/src/main/java/com/cryptoradar/signal/service/SignalEngine.java` consumes funding and aggregates with other deriv features.

What's missing:

- A `BasisService` that joins spot price + perp price every minute and stores `basis_pct` as a hypertable column.
- A basis-percentile score (current basis relative to its trailing 30-day distribution) — the raw level is less informative than the relative position because regimes shift.
- Integration into the Derivatives dimension or as a standalone dimension.

## Implementation sketch

A useful "basis sentiment" feature in one service week:

- **New module**: `BasisService` inside `derivatives-service` (lives close to where funding data already flows).
- **Data flow**: every 60s, for each of the 13 symbols, fetch Bybit perp mark and Binance/Coinbase spot last-price, compute `basis_pct`, persist to a new `basis_history` hypertable.
- **Derived feature**: rolling 30-day basis percentile per symbol. A basis at the 95th percentile of its 30-day distribution is "extreme positive"; at the 5th percentile is "extreme negative".
- **Dimension integration**: extend the Derivatives dimension calculation in `signal-service` to include `basis_percentile_score`:
  - basis at p99+ → −15 contribution to LONG (contrarian bearish skew)
  - basis at p1- → +15 to LONG
  - basis in p25-p75 → 0 contribution (neutral regime)
- **Signal overlay**: extreme-basis flag surfaced in `SignalOverview` for the frontend, alongside the existing regime badge.
- **Empirical validation before shipping**: backtest the basis-extreme thresholds on our 14-day window to confirm the contrarian thesis holds in our specific universe. If a "basis > p95" signal didn't precede a SELL outperformance, the feature stays unwired until we have a longer sample.
- **Effort**: 3-5 days including the backtest and deployment marker.

A more ambitious version: implement the **Bitfinex-style margin-lending rate** as an alternate basis input, but that requires Bitfinex API integration we don't currently have.

## Sources

1. **Bitfinex Pulse — Basis Research Series (2018-2022).** https://blog.bitfinex.com/ — Bitfinex's historical analysis posts documenting basis dynamics in crypto perpetuals and margin-lending markets. Useful for understanding the contrarian-basis thesis from a venue's perspective.
2. **Keynes, J. M. (1930). *A Treatise on Money*. Macmillan.** — Source of the "normal backwardation" theory and the framework for understanding why futures prices systematically differ from spot expectations.
3. **Hull, J. C. (2017). *Options, Futures, and Other Derivatives* (10th ed.). Pearson.** — Chapter 5 covers cash-and-carry arbitrage and the basis convergence mechanism in traditional futures markets; the analogue for crypto perps is straightforward.
4. **Glassnode — "The Bitcoin Futures Basis as a Sentiment Indicator." (Research note, 2021).** https://insights.glassnode.com/ — Glassnode's on-chain analytics applied to derivatives data, including how the basis maps to spot accumulation/distribution flows.
5. **Liu, Y., & Tsyvinski, A. (2021). "Risks and Returns of Cryptocurrency." *Review of Financial Studies*.** https://academic.oup.com/rfs/article/34/6/2689/5917119 — Peer-reviewed analysis of crypto futures-spot relationships and their predictive power for returns.
6. **Alexander, C., Heck, D. F., & Kaeck, A. (2022). "The Role of Binance in Bitcoin Volatility Transmission." *Applied Mathematical Finance*.** https://www.tandfonline.com/doi/full/10.1080/1350486X.2022.2143274 — Empirical study of how perpetual futures flow on Binance transmits volatility to spot markets — relevant for understanding why basis carries information.
