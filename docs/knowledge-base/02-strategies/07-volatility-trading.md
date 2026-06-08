# Volatility Trading

> Buy volatility when implied is cheap relative to realised; sell volatility when implied is rich. Two trades with opposite payoffs, opposite skews, opposite operational profiles. We do the long side; we do not do the short side.

## Definition

A volatility trade is a position whose primary exposure is to the magnitude of price moves, not their direction. The vehicle is almost always an option combination structured to be delta-neutral at entry:

- **Long straddle**: long one ATM call + one ATM put. Profits if the underlying moves substantially in either direction; loses if the underlying sits.
- **Long strangle**: long one OTM call + one OTM put. Same convex payoff shape, cheaper to enter (lower vega density), wider breakeven distance.
- **Short straddle/strangle**: the inverse — sell the call+put, collect the premium, profit if the underlying sits. Theta-positive, vega-negative. *Unlimited loss exposure if the underlying breaks out.*
- **Iron condors / butterflies**: capped-risk versions of short-vol structures (sell ATM, buy further-OTM protection on both wings).

The decision rule is the **IV vs RV spread**. Implied volatility is what the option premium implies given the Black-Scholes (or model-of-choice) inversion. Realised volatility is what the underlying has actually done — `RV = σ(log returns) × √(annualisation factor)`. When IV << RV, options are systematically cheap relative to what the underlying does; long-vol is positive expectancy. When IV >> RV, the opposite — and the volatility risk premium (VRP) is the structural reason short-vol is profitable on average.

The VRP is real and well-documented (Bollerslev, Tauchen & Zhou 2009 for equity indices; Alexander & Imeraj 2023 for crypto): on average, IV > RV by a few percentage points annualised in mature equity markets. The "sell-vol" trade is therefore a positive-expectancy strategy *on average*, with catastrophic tail risk when the average reverses violently.

Crypto markets exhibit a much larger VRP than equities, *but also far more frequent and severe vol explosions*. The 2020-2024 period saw IV/RV ratios oscillate from 0.6 (cheap implied) in calm phases to 2.5+ in spike phases — a much wider range than SPX (typically 0.85-1.30).

## When it works

### Long vol (we do this side)

- **Compressed IV before a known catalyst.** ETH ATM IV at 35% one week before an FOMC meeting that historically moves crypto 5-8% is structurally cheap. Buy the straddle, let the catalyst happen.
- **Realised vol regime change.** A symbol that has been consolidating for 2+ weeks (RV low) but where derivatives positioning is extreme — long-short ratio skewed, OI building, funding flat — is statistically prone to a breakout. IV often hasn't priced this yet because option flow is slow to anticipate it.
- **Crypto post-halving phases.** Historically BTC post-halving (May 2020, May 2024) saw vol re-expansion in the 3-6 month window after the event. Buying calendar vol with strikes around current spot has been a positive-expectancy trade across the last three cycles.
- **Cross-section: alts vs majors.** Alt RV consistently exceeds BTC/ETH RV; if alt IV is priced near BTC/ETH IV, the alt straddle is structurally rich in convexity. The capacity here is small because alt options are thinly traded.

### Short vol (we explicitly do not do this)

- High IV/RV ratios in mature, calm regimes (range-bound spot, well-known catalysts already passed).
- Defined-risk variants (iron condors) where tail losses are capped by long wings.
- Diversified across symbols and expiries to dilute single-name vol explosions.

## When it fails

### Long vol failure modes

- **IV decays faster than spot moves.** A straddle bought 30 days from expiry suffers theta decay every day the underlying sits. If RV stays at the level that priced IV before entry, the trade bleeds.
- **IV crush after a catalyst.** Bought the straddle before earnings / FOMC / a launch event, the catalyst happens, spot moves modestly, but IV collapses from 80% to 45%. The vega loss exceeds the delta gain — net negative.
- **Wrong strike, wrong tenor.** Buying a 7-day ATM straddle in crypto only profits if the move happens in 7 days. The same move in 15 days returns negative because the option you bought is expired or near-zero.
- **Bybit options liquidity.** The order book on Bybit options outside BTC/ETH ATM is thin. Slippage on a strangle entry (and exit) can erase the IV/RV edge. Our `options-service` (`services/options-service/`) currently only watches BTC and ETH for this exact reason.
- **Misestimating RV.** RV measured over the wrong window (7d vs 30d) gives wildly different signals. Crypto RV is regime-conditional; using full-sample RV during a structural vol shift produces stale forecasts.

### Short vol failure modes (the reason we don't run it)

- **Vol explosions wipe years of premium.** A short straddle in BTC at $60k IV=50% earning premium for 6 months loses years of premium in a single 15% spot move when IV doubles to 100%. The empirical record (Mar 2020, May 2021, Nov 2022 — all spike events) is unambiguous about this.
- **Path dependence.** Even if the underlying ends at the strike, intra-period moves can force a hedger to delta-hedge into unfavourable prices ("gamma scalping the wrong way"). Realised hedging cost can exceed the premium collected.
- **Exchange margin spirals.** A short option position on Bybit that goes against you can hit auto-liquidation as IV spikes. The position closes at the worst possible price.
- **The XIV blowup precedent.** Feb 5 2018, the VelocityShares XIV ETN — a short-vol product — lost 96% in a single day when VIX doubled. Equivalent crypto products (and DIY short-vol on Bybit) have the same risk shape.

## What we do today (in projectr-x)

The `options-service` runs the long-vol identification pipeline:

- **`OptionsCollectorService`** (`services/options-service/src/main/java/com/cryptoradar/options/service/OptionsCollectorService.java`) — polls Bybit V5 option chain for BTC and ETH, persists per-contract snapshots (bid, ask, IV, delta, vega, theta, open interest) to the `option_snapshots` hypertable.
- **`RealizedVolService`** (`services/options-service/src/main/java/com/cryptoradar/options/service/RealizedVolService.java`) — computes annualised RV from the shared `candles` hypertable using log-return stdev × √365. 7-day and 14-day variants supported.
- **`SignalOverlayService`** — fuses our signal-side outputs (overall dimension score, regime, top-opportunity flag) into a 0-100 score that biases opportunity selection toward symbols where we have an independent directional view.
- **`OpportunityScorer`** — the decision module. Formula:
  ```
  iv_rv_gap_score = clamp((rv14d − iv_atm) / rv14d × 100, 0, 100)
  confidence      = 0.6 × iv_rv_gap_score + 0.4 × signal_overlay
  ```
  Persists a row to `option_opportunities` when `confidence ≥ 70` (configurable via `options.opportunity.confidence-threshold`). A 60-minute dedup cooldown prevents the 60s scoring loop from flooding the table.

The strategy is **strictly long-vol**. The scorer's design biases toward `(rv − iv) > 0` setups — strangles purchased when the underlying has been moving more than the option chain has priced in. There is no short-vol code path and we have no intention of adding one until the operational stack supports rigorous margin-spiral handling.

The scorer picks ATM strangles or nearest-OTM straddles via `pickStrangle` — closest call and closest put to spot, premium = call_ask + put_ask. The bias toward asks (taker-paying) reflects the same single-source-of-truth taker assumption used in `trade-execution-service`.

Currently the opportunities are surfaced as alerts in `OpportunityPublisher` (Redis pub/sub) and consumed by the frontend. **We do not auto-execute option trades** — the operational complexity (margin, multi-leg, exercise/expiry handling) hasn't been built. Human-in-the-loop is the current model.

## Implementation sketch (extensions)

Several paths to extend without crossing into short-vol territory:

- **Calendar spreads (long-vol, defined-risk).** Buy a longer-dated ATM straddle, sell a shorter-dated one against it. Profits from a steep IV term-structure (front cheap, back rich). Same data flow as current strangle scorer; needs a new opportunity type and a 2x2 leg pricer.
- **Realised-vs-implied for alt vol.** When Deribit lists more altcoin options or when we add a Deribit feed alongside Bybit, alt-IV becomes investable. Same `RealizedVolService` plumbing.
- **Hedged delta exposure.** Use a long-vol straddle as a hedge against a directional Bybit perp position — when our signal fires and the IV is low, buy a small long-vol position to convexify the trade. Half-implemented in concept via `SignalOverlayService`; not yet wired to execution.
- **Auto-execute opportunities.** Build option-execution into `trade-execution-service` — multi-leg order placement, margin reservation, expiration/exercise scheduler. Effort: ≥3 weeks; gated on operational sign-off because the failure modes are nastier than perp execution.

## Sources

1. **Sinclair, E. (2013). *Volatility Trading* (2nd ed.). Wiley.** — Practitioner's bible. Covers IV/RV decomposition, gamma scalping, hedging vega, the failure modes of short-vol and how to mitigate them. Required reading for anyone running a vol book.
2. **Natenberg, S. (1994). *Option Volatility & Pricing: Advanced Trading Strategies and Techniques*. McGraw-Hill.** — Classical practitioner option-pricing reference. Greeks, term structure, skew, and the geometry of every common option combination.
3. **Bollerslev, T., Tauchen, G., & Zhou, H. (2009). "Expected Stock Returns and Variance Risk Premia." *Review of Financial Studies*.** https://academic.oup.com/rfs/article/22/11/4463/1581014 — Establishes the volatility risk premium empirically in US equity indices; explains why short-vol is positive expectancy on average.
4. **Alexander, C., & Imeraj, A. (2023). "Inverse Options in a Black-Scholes World." *Quantitative Finance*.** https://www.tandfonline.com/doi/full/10.1080/14697688.2023.2200565 — Recent academic treatment of crypto option pricing on inverse-quoted contracts (Deribit, Bybit USDC options); relevant for IV measurement under different settlement conventions.
5. **Hou, A., Wang, W., & Chen, C. Y.-H. (2020). "Pricing cryptocurrency options." *Journal of Financial Econometrics*.** https://academic.oup.com/jfec/article/18/2/250/5610586 — Empirical analysis of BTC option pricing, jump-diffusion vs Black-Scholes fit, and the structural IV-RV relationship in crypto specifically.
6. **Taleb, N. (1997). *Dynamic Hedging: Managing Vanilla and Exotic Options*. Wiley.** — Hardcore practitioner treatment of why hedging costs eat option premiums and where the failure modes of "selling vol" come from. Good complement to Sinclair.
