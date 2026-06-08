# Plan — Alternative volatility strategy (Sinclair / López de Prado)

> Companion to the existing long-vol strangle scorer. Captures the *opposite* regime — when implied vol substantially exceeds realized vol — using **defined-risk** structures only. Pre-registered: no naked sells, ever. Lopez de Prado's evaluation discipline applied throughout.

## Motivation

The current `OpportunityScorer` searches for **IV << RV** ("options are cheap, buy a strangle"). The diagnostic endpoint confirms this is rare in practice — vol risk premium (VRP) means IV typically trades 30–60% above RV in calm markets. **Every diagnostic snapshot in the current data shows confidence=0** because every underlying sits in the opposite regime.

That regime is also tradeable, just from the other side. Sinclair's *Volatility Trading* documents the VRP as one of the most robust risk premia in derivatives markets. Lopez de Prado provides the evaluation framework that prevents us from over-fitting to it.

**This is a PLAN, not an implementation.** Each tier is pre-registered with success criteria. Nothing executes real orders until Tier 4, which requires user approval AND ≥30 paper-closed setups proving edge.

## What we will explicitly NOT do

| Practice | Why excluded | Sinclair on it |
|---|---|---|
| Naked short straddles / strangles | Unbounded loss; one Luna-style event ruins the account regardless of historical VRP edge | Ch. 8: "selling options without defined risk is a leveraged bet against tail risk" |
| Selling vol in the 24h before a known scheduled event | Event premium is *priced* — IV exceeds RV because the event is real | Ch. 11 — event vol structurally elevated |
| Full Kelly sizing on vol trades | Vol-of-vol means Kelly variance estimates underestimate drawdown | Thorp / Sinclair — quarter-Kelly with vol haircut |
| Selling deep ITM options "for premium" | Skew suggests the market correctly prices the asymmetry; we have no edge there | Ch. 6 on skew interpretation |

## Tier 1 — Scoring-only (NO execution)

**Add a `ShortVolOpportunityScorer` next to the existing long-vol one.** Fires when conditions favor a defined-risk short-vol structure. Persists alerts to a new `option_short_vol_opportunities` table, surfaces in the UI alongside long-vol opportunities. No order placement.

### Scoring formula

```
confidence_short = 0.5 × ivRvPremiumScore
                + 0.2 × termStructureScore
                + 0.2 × signalQuietScore
                + 0.1 × ivPercentileScore

ivRvPremiumScore   = clamp(0, 100, (iv_atm - rv14) / rv14 × 50)     // mirror of gap score
termStructureScore = 100 - skew_steepness × 100                     // flat term = OK; steep = event premium
signalQuietScore   = 100 - overlay_score                            // signal engine SILENT = good for short vol
ivPercentileScore  = current_iv_percentile_30d × 100                // sell vol when vol is rich vs its own history
```

**Why this shape:**
- The long-vol scorer's `signalOverlay` measures *coming directional move probability*. For short-vol we want the *opposite* — quiet markets where realized vol is unlikely to exceed implied.
- `termStructureScore` filters event premium — if 7d IV >> 30d IV, the market is pricing a near-term event we have no edge on.
- `ivPercentileScore` is from Sinclair Ch. 7: trade vol mean-reversion only when current vol is in an elevated percentile of its own history.

### Threshold + dedup

| Setting | Default | Rationale |
|---|---|---|
| `short-vol.confidence-threshold` | 80 | Higher than long-vol's 75 — short vol has unbounded *unhedged* loss tail, want strong evidence |
| `short-vol.dedup-cooldown-minutes` | 240 | 4 hours — short-vol setups persist for hours, not minutes |
| `short-vol.min-iv-rv-spread-pct` | 25 | IV must exceed RV by at least 25% absolute to even consider — VRP threshold |

### Pre-registered success for Tier 1

After **30 days** of running Tier 1 (alert-only):
- ≥10 setups detected per week
- ≥60% of resolved setups would have produced positive R if a hypothetical iron condor at picked strikes had been held to expiry
- No false-fires in the 24h preceding any major event (BTC ETF decisions, halvings, Fed rate days, exchange listings on tracked underlyings)

If those don't hold, the strategy is shelved — the scoring is wrong, not the execution.

### Effort: ~1 day

- New `ShortVolOpportunityScorer.java` (~150 LOC, mirrors structure of existing `OpportunityScorer`)
- New `option_short_vol_opportunities` table (one migration)
- Reuse: `RealizedVolService`, `SignalOverlayService`, `OptionSnapshotRepository`, `OpportunityEnricher`
- New methods needed: `RealizedVolService.computePercentileVsHistory(underlying, period, lookbackDays)`, `OptionSnapshotRepository.ivTermStructure(underlying)`
- Add to existing `OptionsScheduler` as a 4th `@Scheduled` method
- Extend `/api/options/diagnostic` to show short-vol scoring too

## Tier 2 — Defined-risk structures

**Replace "single straddle/strangle" with picked iron condor or credit spread.** Each opportunity row carries:

- Short leg (the strike we sell) — chosen at delta 0.20–0.30 (Sinclair recommends 1-stdev OTM)
- Long leg (the hedge) — same expiry, 1–2 strikes further OTM
- **Max loss** explicitly computed: `(short_strike − long_strike) × multiplier − net_credit`
- **Break-even** computed: `short_strike ± net_credit`
- **POP** (probability of profit) computed: `1 - delta_of_short_leg` (approximation from Sinclair Ch. 5)

This is the structural difference that makes short-vol tradable for a small account. The long leg caps loss; we know exactly what we can lose before opening.

### What gets persisted (extends `OptionOpportunity`)

| New field | Type | Source |
|---|---|---|
| `structure_type` | text | `'IRON_CONDOR'` / `'CREDIT_SPREAD_CALL'` / `'CREDIT_SPREAD_PUT'` |
| `short_call_symbol` / `short_put_symbol` | text | Picked legs |
| `long_call_symbol` / `long_put_symbol` | text | Hedge legs |
| `net_credit` | numeric | Total premium received |
| `max_loss_usd` | numeric | Bounded — used by sizer |
| `pop_pct` | numeric | Probability of profit at entry |
| `break_even_low` / `break_even_high` | numeric | Spot bands |

### Effort: ~2 days

- Picker function extending the current `pickStrangle` pattern
- Schema migration to add the new fields
- Frontend card variant (`ShortVolOpportunityCard`) — different colour scheme so users don't confuse long-vol BUY signals with short-vol SELL signals

## Tier 3 — Lopez de Prado evaluation framework

Apply the López de Prado discipline to BOTH the existing long-vol and the new short-vol scorers. The evaluation lives one level above strategy code — it scores the *strategies themselves*, not individual setups.

### 3a. Triple-barrier outcome labeling

For each detected opportunity (regardless of execution), label the realized outcome by simulating a paper hold:

| Barrier | Definition |
|---|---|
| **Profit barrier** | Long-vol: realized spot move ≥ break-even at expiry → labeled `WIN`. Short-vol: realized spot stayed within break-even bands → `WIN`. |
| **Loss barrier** | Long-vol: realized move < break-even AND IV crashed (vega loss) → `LOSS`. Short-vol: spot breached break-even band → `LOSS`. |
| **Time barrier** | Expiry reached without barrier hit → labeled `EXPIRED` with final P&L value. |

Stored in `option_opportunities.outcome_label` (`WIN` / `LOSS` / `EXPIRED`) plus `outcome_pnl_pct`. This unlocks the hit-rate strip on `/options` that's been hidden waiting for data.

### 3b. Deflated Sharpe per strategy

Compute deflated Sharpe (Bailey & López de Prado, SSRN 2460551) for the long-vol scorer AND the short-vol scorer separately, every 30 closed setups. The formula penalizes:
- Multiple testing (we've iterated config many times — see `deployment_markers`)
- Non-normal returns (vol strategies have fat tails)

If deflated Sharpe < 0.5 over the previous 100 setups, **kill the strategy automatically** and log a deployment marker.

### 3c. Purged walk-forward backtest

Whenever a scorer parameter is changed (threshold bumped, weight rebalanced), run a purged k-fold walk-forward backtest against the last 90 days of `option_snapshots` BEFORE the change goes live. Tooling: extend the existing `backtest-service` module to handle the option-strategy variant.

### Effort: ~3 days

- Triple-barrier outcome backfill job (~0.5d)
- Deflated Sharpe scorer (pure math, ~0.5d)
- Walk-forward extension to backtest-service (~2d)

## Tier 4 — Execution (DEFERRED — requires user approval)

**Do not implement until all of the following hold:**
1. Tier 1 has produced ≥30 *resolved* short-vol setups
2. Tier 3 deflated Sharpe > 1.0 over the most recent 100 short-vol setups
3. User has explicitly approved going from alert-only → live trades
4. Bybit account has options trading enabled (currently only perp trading)

### Execution constraints (binding)

| Constraint | Value | Why |
|---|---|---|
| Max concurrent short-vol positions | 2 | Crypto vol can spike together across underlyings; correlation in tail events is ~1.0 |
| Max risk per trade | 0.5% of equity | Half of perp-side `risk_percent` since defined-risk doesn't mean *low* loss |
| Max combined vega | −500 vega | Limits the portfolio's loss-on-IV-spike to a known band |
| Position close trigger | Either profit ≥ 50% of max credit (Sinclair Ch. 9) or loss ≥ 50% of max loss | Asymmetric — capture wins faster than losses |
| Forced flat | 4h before any flagged event | Tier 1's event filter is for entry; need explicit close logic for active positions too |

### Effort (when approved): ~4 days

Spans `trade-execution-service` (multi-leg Bybit options orders), `MarketRegimeService` (event detection), and frontend (defined-risk position card).

## Implementation order

```
Tier 1 (1 day) → 30-day observation window → Tier 2 (2 days) → another 30-day window
                       │                            │
                       └── if criteria miss, STOP   └── if criteria miss, STOP
                                                          │
                                                          ↓
                                                    Tier 3 (3 days)
                                                          │
                                                          ↓
                                              Tier 4 — user approval required
```

Total elapsed: **~70 days** from Tier 1 start to live execution, IF every pre-registration passes. Two go/no-go decision points BEFORE any real money is at risk.

## What this gives us today

Once Tier 1 lands:
- The `/api/options/diagnostic` endpoint extends to show both scorers side-by-side
- The watchlist will have *something to display* in the current market regime (every underlying scores high on the short-vol side per the data we just pulled)
- We accumulate the outcome data needed to validate the strategy before risking capital

## Cross-references

- `02-strategies/07-volatility-trading.md` — theory primer (already in KB)
- `04-quant-methods/03-triple-barrier-labeling.md` — López de Prado method (already in KB)
- `04-quant-methods/06-deflated-sharpe.md` — Bailey/LdP correction (already in KB)
- `05-risk-and-execution/01-position-sizing-kelly.md` — Kelly fractional sizing
- `06-derivatives/06-straddles-and-strangles.md` — current long-vol picker theory
- `services/options-service/.../OpportunityScorer.java` — pattern to mirror for short-vol scorer

## Sources

1. **Sinclair, Euan — *Volatility Trading* (2nd ed., 2013)** — Wiley. The canonical short-vol risk-controlled framework. Ch. 5 (POP), Ch. 7 (vol percentile entries), Ch. 8 (defined risk only), Ch. 9 (exit rules), Ch. 11 (event vol).
2. **López de Prado — *Advances in Financial Machine Learning* (2018)** — Wiley. Ch. 3 (triple-barrier), Ch. 14 (backtest statistics).
3. **Bailey & López de Prado, *Deflated Sharpe Ratio* (2014)** — SSRN 2460551. The deflation formula for the multiple-testing correction.
4. **Coval & Shumway, *Expected Option Returns* (2001)** — *Journal of Finance*. Original empirical documentation of the vol risk premium.
5. **Bondarenko, *Why Are Put Options So Expensive?* (2014)** — *Q. Journal of Finance*. Specifically on the put-side premium that's the strongest VRP component.
