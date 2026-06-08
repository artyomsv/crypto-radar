# Long-Vol Setups: Straddles and Strangles

> A straddle (same strike, call+put) or strangle (different strikes, call+put) is the simplest long-volatility trade. You pay theta every day and get paid when realized vol exceeds the implied vol you bought in at. The break-even math determines whether the trade is worth it.

## Definition

### Straddle

A **long straddle** is one long call + one long put, both at strike `K`, same expiry `T`. Total premium paid = `C + P`. At expiry, payoff is `|S_T − K|` minus initial premium.

**Break-evens** at expiry:
- Upper: `K + (C + P)`
- Lower: `K − (C + P)`

The position profits whenever `S_T` moves outside the break-even band — i.e. whenever realized moves exceed the implied move priced into premium. Delta starts near zero (calls and puts cancel) and gains absolute delta as spot drifts off-strike. This is **long gamma**: rebalancing delta over the life of the trade harvests realized vol.

### Strangle

A **long strangle** is one long OTM call (strike `K_c > S`) + one long OTM put (strike `K_p < S`), same expiry. Cheaper than straddle (OTM premiums smaller), but wider break-evens.

**Break-evens** at expiry:
- Upper: `K_c + (C + P)`
- Lower: `K_p − (C + P)`

Strangles need bigger moves to pay off, but you pay less to enter — the trade is "wider wings for cheaper, but you need them to actually pay."

### Delta-neutral vs naked

**Naked** straddle/strangle: enter at trade time, leave the delta drift alone. PnL at expiry depends on terminal `S_T`, not on the path.

**Delta-neutral** (gamma trading): rehedge delta back to zero at intervals (every 5%, every day, every Vega-pop). Each rehedge sells high / buys low along the spot path. Total PnL becomes a function of **realized vs implied gamma**: `0.5 × Γ × σ² × S² × T` proxies the expected accumulated rehedge profit.

Naked strangle = bet on a single big move. Delta-neutral strangle = bet on path volatility. They are different trades dressed in the same legs.

## Break-even math (worked example)

ATM BTC straddle, 14-day expiry, S = 60,000, IV = 60%, premium ≈ `0.4 × σ × S × √(T/365) ≈ 0.4 × 0.60 × 60000 × √(14/365) ≈ 2,820`.

Break-evens: `60,000 ± 2,820 = [57,180; 62,820]`. To profit at expiry, realized 14d move must exceed ±4.7%.

In annualized vol terms, the break-even is the IV you paid: a 60% IV straddle break-evens if realized 14d vol matches 60%. Above 60%, the buyer wins. Below, the seller does.

Strangles widen the break-even band but reduce the absolute loss if neither happens — you forfeit the smaller premium instead of the larger one.

## When it works

- **IV materially below RV.** The base case. When the market is asleep but the realized series has been swinging, premium is cheap and the strangle's positive expected value is mechanical.
- **Pre-event positioning.** Known catalyst within the option's lifetime (FOMC, scheduled token unlock, ETF decision) and the surface hasn't yet priced it. Front-month ATM IV that hasn't pumped is the entry. After the event, regardless of direction, IV resets lower and the position closes.
- **Regime transitions.** End of long chop period + breakout into a new range. The chop kept IV depressed; the breakout is where strangles pay.
- **Diversified vol portfolio.** Sinclair's argument: a basket of 30+ uncorrelated long-vol positions across symbols/expiries has lower drawdowns than any single position. The IV-RV gap is mean-reverting in aggregate even if not always per name.

## When it fails

- **Theta bleed in pure chop.** Buy a 30d ATM straddle the day before a 30d sideways grind = full premium loss. Time decay is non-linear and brutal in the final two weeks.
- **IV crush after the event.** Even if you correctly anticipated the event, if the price moves less than the IV implied, the straddle loses despite "being right." This is the classic earnings-trade trap — IV bid up to 120% pre-event, prints, vol crashes to 60%, stock moves 3%, straddle loses 40%.
- **Bid-ask round-trip.** Crypto option spreads are 2-10% of premium on illiquid strikes/expiries. The "edge" implied by RV > IV by 5 vol points evaporates if entry+exit costs 8% of premium.
- **Smile-aware Δ-hedging error.** Sticky-strike vs sticky-delta vol assumptions matter. A trader assuming sticky-strike when reality is sticky-delta over-hedges in trending markets and under-hedges in ranging ones.
- **Liquidity disappearance.** When the move you wanted finally happens, market-maker quotes widen exactly when you want to exit. The "10% above intrinsic" you saw on screen pre-move is "5% above intrinsic" the moment everyone wants to close.

## What we do today (in projectr-x)

`options-service` is built around the long-strangle thesis specifically. The end-to-end flow:

1. **`OptionsCollectorService`** pulls Bybit options chain every 60s. Persists each `(underlying, strike, expiry, type)` snapshot into `option_snapshots`.

2. **`OpportunityScorer.scoreOne(underlying)`** — runs every 60s per underlying. Steps:
   - Filter snapshots to the **earliest non-expired** expiry. We deliberately target front-month — longest theta-per-day, biggest IV-RV gap when surfaces are flat, tightest spreads.
   - `pickStrangle(contracts)`: finds the closest-to-spot call and closest-to-spot put. When the closest strikes match (calls strike = puts strike = round number near spot), this is functionally an ATM **straddle**; when they don't match (calls strike just above spot, puts strike just below), it's a tight **strangle**. The code name is "strangle" but the strict definition floats between the two.
   - Compute the IV-RV gap score: `clamp((rv14 − ivAtm) / rv14 × 100, 0, 100)`. Asymmetric — only positive gaps (cheap IV) score.
   - Compute signal-overlay score from `SignalOverlayService` (recent signal density + alignment + abs-R on the underlying).
   - Composite confidence: `0.6 × ivRvGapScore + 0.4 × overlay`. Persist when `confidence ≥ 70` (configurable via `options.opportunity.confidence-threshold`).
   - Dedup: skip insert if `(callSymbol, putSymbol)` was already inserted within `options.opportunity.dedup-cooldown-minutes` (default 60).

3. **Persistence + publish.** `OpportunityPublisher.publish` emits the row on a Redis topic (consumed by frontend for the watch-list view). Premium, IV-ATM, RV7/RV14, IV-RV spread, signal-overlay score, and full chain metadata are all saved on `OptionOpportunity`.

What we deliberately don't do (yet):

- **No automatic execution.** `trade-execution-service` is wired for Bybit V5 USDT-perpetuals. Options execution is a separate API surface (Bybit options use COIN-settled margining, different position management). Opportunities are signal-only.
- **No delta-hedge management.** We don't track theoretical delta over the position lifetime. If/when we wire option execution, the gamma-trading rehedge schedule is the next decision.
- **No spread/butterfly scoring.** Only ATM/near-ATM long-vol legs. Selling vol via strangles is a separate strategy with different risk profile (unlimited downside) and isn't in scope.

Files:
- `services/options-service/src/main/java/com/cryptoradar/options/service/OpportunityScorer.java` — full strangle-selection + scoring logic
- `services/options-service/src/main/java/com/cryptoradar/options/service/SignalOverlayService.java` — the overlay term
- `services/options-service/src/main/java/com/cryptoradar/options/model/OptionOpportunity.java` — persisted row schema

## Sources

1. **Sinclair, *Volatility Trading* (2nd ed., 2013).** The reference text for vol-trader operations. Chapter 5 (straddles/strangles), Chapter 7 (gamma trading), Chapter 11 (when long-vol positions fail in practice). Heavily influences our naked-vs-delta-neutral framing.
2. **Sinclair, *Option Trading: Pricing and Volatility Strategies and Techniques* (2010).** Earlier book by the same author. Chapter 9 break-even math is the source of the worked example above.
3. **Natenberg, *Option Volatility & Pricing* (2nd ed., 2014).** Chapter 10 (volatility spreads). Section on dynamic hedging vs naked positions is the classical reference.
4. **Bennett, *Trading Volatility* (2014).** Chapter 6 (long-vol strategies). https://www.trading-volatility.com/Trading-Volatility.pdf — free PDF.
5. **Carr, Lee (2009), "Volatility Derivatives."** *Annual Review of Financial Economics* 1. https://doi.org/10.1146/annurev.financial.050808.114304 — Theoretical link between vol-spreads and variance-swap payoffs; useful for understanding what a continuously-rehedged strangle actually pays.
6. **Bybit V5 API — Options Tickers.** https://bybit-exchange.github.io/docs/v5/market/tickers — Endpoint spec for the chain data our collector reads.
