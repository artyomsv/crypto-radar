# Options Greeks

> The Greeks are the partial derivatives of an option's price with respect to the variables that drive it: underlying price (Delta, Gamma), volatility (Vega), time (Theta), and interest rate (Rho). Reading them correctly is the difference between holding a vol play and holding a price bet you didn't intend.

## Definition

The Black–Scholes (and any reasonable extension) values a European option as a function of five inputs: spot `S`, strike `K`, time-to-expiry `T`, volatility `σ`, and risk-free rate `r`. The Greeks are the first-order (and one second-order) sensitivities of that price:

- **Delta (Δ)** — `∂V/∂S`. Change in option price per $1 change in underlying. Calls have Δ in `[0, 1]`; puts in `[−1, 0]`. ATM options sit near ±0.5. Delta is also the **hedge ratio**: a Δ=0.40 call requires 0.40 units of short underlying to neutralize price exposure.
- **Gamma (Γ)** — `∂²V/∂S² = ∂Δ/∂S`. Curvature of the price function. Highest for ATM near-expiry options. A high-Γ position means delta swings rapidly as spot moves — short Γ traders rehedge frantically; long Γ traders harvest those rehedges. Gamma is non-negative for both calls and puts when long.
- **Vega (ν)** — `∂V/∂σ`. Change in option price per 1 percentage-point change in implied volatility. ATM options carry the most vega; deep ITM/OTM contracts carry little because they're approaching their intrinsic-value floor. Vega has units of "dollars per vol-point."
- **Theta (Θ)** — `∂V/∂T` (or `−∂V/∂t`, depending on sign convention). Time decay. Long options lose value as expiry approaches; short options gain it. Theta is **non-linear and accelerates near expiry** — the last 5 days of an ATM straddle's life shed value at 3–5× the rate of the first 5.
- **Rho (ρ)** — `∂V/∂r`. Change in option price per 1 percentage-point change in risk-free rate. For short-dated crypto options (Deribit weeklies, Bybit dailies through monthlies) rho is rounding error and we ignore it. It only matters at LEAPS-like maturities (>6 months).

For complex positions (spreads, strangles, condors), the Greeks **add linearly**. A long-call-plus-long-put strangle has the put's negative delta cancel a portion of the call's positive delta — if both legs are equidistant from spot, the position is delta-neutral and is a pure volatility bet. Its vega is the sum of the legs' vegas (both positive), and its theta is the sum of both legs' (negative) thetas.

## When it works

The Greeks are operationally useful when:

- **Hedging:** A market-maker holding inventory uses Δ to compute the underlying hedge, then re-hedges when accumulated Γ × `ΔS` pushes the book out of delta-band. This is the entire business model of an option desk.
- **Reading positioning:** Aggregate dealer-Γ exposure (the "GEX" series published by venues like Skew or Genesis Volatility) predicts realized vol — short-Γ dealer regimes amplify spot moves, long-Γ regimes dampen them.
- **Sizing a vol view:** If you think realized vol over the next 14d will exceed implied, you want a position with high vega and acceptable theta. The Greeks tell you exactly which strike/expiry combination optimizes that trade-off.

## When it fails

- **Smile / skew assumptions:** Black–Scholes assumes a flat volatility surface. Real options trade with a smile and skew (see `04-volatility-surface.md`). Greeks computed under the BS assumption misstate sensitivities for OTM strikes — the actual "smile-aware" greeks differ materially. Sticky-strike vs sticky-delta vol modeling is the standard refinement.
- **Discrete rehedging:** Continuous-time theory assumes rehedging at every infinitesimal move. In practice you rehedge at discrete intervals, and accumulated Γ between rehedges produces "Γ slippage" — long-Γ traders earn it, short-Γ traders pay it. The expected size of this term is roughly `0.5 × Γ × σ² × S² × Δt`.
- **Jump risk:** Crypto markets jump on news, listings, exploits. Black–Scholes models the path as continuous diffusion. A 10% gap-down event blows through the linearized Greeks — your computed Δ-hedge under-protects, your sold puts pay out far more than vega-only PnL suggests.
- **Liquidity disappears at the wings:** Deep-OTM options have wide spreads. The theoretical Vega tells you the position should gain `$X` per vol point, but the bid-ask cost to enter and exit eats half of that on round-trip.

## What we do today (in projectr-x)

`options-service` ingests Bybit's options chain via `OptionsCollectorService` and stores per-snapshot fields including `impliedVol`, `delta`, `gamma`, `vega`, `theta` on the `OptionSnapshot` model. The Greeks are taken from Bybit's published values (Bybit computes them under a BS engine internally and exposes them on the chain). We do not currently re-derive Greeks — that's a deliberate choice to avoid baking a specific vol-model into the scoring pipeline before we have a use case for it.

`OpportunityScorer.pickStrangle` ignores individual leg Greeks and only uses ask premium + ATM IV. A future enhancement (`OptionSnapshot` already carries the columns) would weight strangle selection by vega-per-premium-dollar — buying vol cheap is the goal, and that's exactly the vega/premium ratio.

### Practical Greek arithmetic for the long-strangle holder

For the strangle position the scorer detects, the per-Greek narrative is:

- **Delta**: starts near zero when the call and put are roughly symmetric around spot. Drifts positive when spot rises (the call's delta grows toward 1 while the put's decays toward 0); drifts negative when spot falls. The trader without delta-hedging is implicitly accumulating directional exposure as the trade matures — a fact our scorer does not currently communicate to consumers.
- **Gamma**: highest at trade open when both legs are near-ATM. Decays as spot drifts off the strikes or as expiry approaches. The whole reason long-strangle works in theory is positive gamma + a market that moves enough to realize it.
- **Vega**: the position is *long* vega on both legs. An IV pump of 5 vol points across the surface immediately increases the position's mark value by `vega_call + vega_put`. This is the "you bought vol cheap, vol got expensive, you won" payoff path — independent of whether spot actually moved.
- **Theta**: negative on both legs. Time decay is the cost of being long-vol. Front-month strangles decay 1.5–2% of premium per day in the final week. The trade has to either (a) realize enough gamma to cover theta, or (b) see an IV pump that exits the position before theta dominates.

### Higher-order Greeks (briefly)

Three second-order Greeks practitioners watch but we do not model:

- **Vanna** = `∂Δ/∂σ` = `∂Vega/∂S`. How delta moves with vol changes, equivalently how vega moves with spot. Important for skew traders.
- **Vomma** (or volga) = `∂Vega/∂σ`. Curvature of vega in vol. Matters for trades that win on big vol moves (the "wings" of the vol surface).
- **Charm** = `∂Δ/∂t`. How delta decays with time. Matters for delta-neutral hedgers managing positions through expiry.

None of these are in our scoring pipeline today. They become relevant if and when we start delta-hedging or skew-trading rather than buying naked vol exposure.

Files:
- `services/options-service/src/main/java/com/cryptoradar/options/model/OptionSnapshot.java` — schema with Greek fields
- `services/options-service/src/main/java/com/cryptoradar/options/service/OptionsCollectorService.java` — ingestion from Bybit `/v5/market/instruments-info` + `/v5/market/tickers?category=option`

## Sources

1. **Natenberg, *Option Volatility & Pricing* (2nd ed., 2014).** The reference text. Chapter 6 (Delta) and Chapter 7 (Gamma, Theta, Vega) define each Greek with worked examples; Chapter 13 covers position-level Greek arithmetic for spreads.
2. **Hull, *Options, Futures, and Other Derivatives* (10th ed., 2018), Chapter 19.** Derivation of each Greek from the Black–Scholes PDE; the standard textbook treatment.
3. **Carr, Madan (2001), "Optimal Positioning in Derivative Securities."** https://www.tandfonline.com/doi/abs/10.1080/713665548 — Theoretical framing of how to use Greeks to express specific views (jumps, vol-of-vol, skew) cleanly.
4. **Bybit V5 API — Get Tickers (Option category).** https://bybit-exchange.github.io/docs/v5/market/tickers — Documents the `delta`, `gamma`, `vega`, `theta`, `markIv` fields we ingest.
5. **Sinclair, *Volatility Trading* (2nd ed., 2013), Chapter 4.** Practitioner discussion of why Greeks computed under flat-vol assumptions mislead, and how to adjust for sticky-strike / sticky-delta regimes.
