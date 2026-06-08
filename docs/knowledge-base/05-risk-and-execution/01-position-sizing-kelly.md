# Position Sizing — Kelly, Fractional Kelly, and Why Nobody Runs Full Kelly

> The math says bet `f* = edge/variance` of your bankroll. The math also says ~50% of the time you'll be down 50% before you recover. No one survives full Kelly emotionally; everyone runs a fraction.

## Definition

The **Kelly Criterion** is the bet-sizing fraction that maximizes the long-run expected growth rate of capital under repeated independent wagers with known edge and variance. For a binary bet that wins fraction `b` of the time and pays odds `c:1` against a loss of `1`, Kelly's formula is:

`f* = (b·c − (1 − b)) / c`

For a continuous-return setting (closer to trading), the analog is `f* ≈ μ / σ²` where `μ` is expected return per trade and `σ²` is the variance per trade. Equivalently in R-multiples: `f* = E[R] / Var(R)`.

The result Edward Thorp proved in his Kelly papers — and used to crush blackjack tables in the 1960s, then to run Princeton Newport Partners at a Sharpe in the high single digits for two decades — is that betting any fraction `f > f*` produces *negative* long-run growth almost surely. Betting `f < f*` produces positive growth but at a lower rate. Betting `f = f*` is asymptotically growth-optimal.

Two practical caveats are equally important:

1. **Kelly maximizes log-wealth, not utility.** The path to growth-optimal goes through deep drawdowns. The classic Thorp result: under full Kelly, the probability of drawing down to half your starting bankroll at some point before doubling it is ~50%. Most humans cannot continue trading rationally through a 50% drawdown — they reduce size, change strategy, or quit.
2. **Real edge and variance are estimated, not known.** Kelly's optimality is conditional on perfect knowledge of `μ` and `σ`. Over-estimate `μ` by 20% and you bet ~50% over Kelly — straight into the negative-growth zone.

**Fractional Kelly** — bet `k · f*` for `k ∈ (0, 1)`, typically `k = 0.25` to `0.5` — sacrifices a fraction of optimal growth in exchange for materially smaller drawdowns. The growth rate under fractional Kelly is approximately `f² (2k − k²) · (μ²/σ²) / 2`; at `k = 0.5` you still capture ~75% of the optimal growth rate while halving the drawdown variance. Practitioners universally run fractional. Ed Seykota: "Everybody gets what they want out of the market. […] If you want excitement, you trade big. If you want to compound, you trade small."

## When it works

- **Edge is well-estimated and stable.** If you have a 5-year out-of-sample track record, your `μ̂` and `σ̂` are reasonable plug-ins. Below that, you're guessing.
- **Bets are independent.** Sequential coin flips, sure. Crypto trades on correlated alts, not really — see `06-portfolio-correlation.md`. Multi-asset Kelly is a much harder optimization that accounts for the covariance matrix; ignoring covariance double-counts edge.
- **You can survive the path.** Quarter-Kelly traders typically see max drawdowns of ~15–25%; half-Kelly traders ~30–40%. Full-Kelly traders see ~50%+ and have to keep going.

## When it fails

- **`μ` is over-estimated.** Almost always the failure mode for retail. Backtest edges shrink in live trading (selection bias, fees, slippage, regime change). A live `μ` that's 30% lower than the backtest pushes you well over Kelly.
- **Returns are heavy-tailed.** Kelly's variance term assumes a finite, stable σ². Crypto has both finite-σ regimes and crisis regimes where σ blows up; betting Kelly-optimal in calm times and not rescaling for crisis is the recipe for blowup.
- **Bets aren't simultaneous.** If you have 10 open positions, the Kelly fraction for each must be reduced — they're competing for the same risk capital. The naive multi-position Kelly = "Kelly per position" is reliably over-leveraged.
- **Path-dependence ignored.** Stops, leverage, liquidation — these all introduce path effects Kelly doesn't model. A 3x-leveraged Kelly position on a Bybit perp can liquidate at a drawdown the unleveraged Kelly model says is normal.

## What we do today (in projectr-x)

The `trade-execution-service` does **not** run Kelly sizing. Position sizing is determined by:

- A **fixed risk-per-trade** percentage from `ExchangeAccount.riskPerTradePercent` (default 1%). Each trade's notional is computed so that hitting the initial stop loses exactly that percent of equity.
- A **maximum concurrent position cap** from `ExchangeAccount.maxConcurrentPositions` (default 10) — caps simultaneous exposure regardless of edge.
- A **daily loss kill-switch** of `ExchangeAccount.maxDailyLossPercent` (default 7%, 10% on account 297 DEMO) — `DailyPnlCalculator` computes today's realized PnL as a percent of cached Bybit equity and trips `GuardrailPolicy` to halt new entries when hit.
- A **conservative default leverage** of 3x — see `05-leverage-and-liquidation.md` for why.

The 1% risk-per-trade with a 10-position cap means **maximum simultaneous risk is 10%** — far less than even quarter-Kelly would suggest given our +0.118R expectancy and ~1.5R standard deviation across the v4 sample. A naive Kelly calc on those numbers: `f* ≈ 0.118 / 1.5² ≈ 5.2%` per trade. Quarter-Kelly: 1.3%. Our 1% is conservative-quarter-Kelly territory, which is the right place to be given our small sample (272 trades) and short history (6 weeks).

The reason we don't compute Kelly explicitly is **statistical hygiene**, not theoretical disagreement. With per-cell n < 30, our estimate of `μ` per symbol/strategy/regime is so noisy that the Kelly fraction it produces would swing 5x week-to-week. A fixed 1% is a Bayesian prior of "we don't know our edge yet, bet small." That's the right answer until walk-forward (`07-walk-forward-analysis.md`) gives us per-cell estimates with reasonable confidence intervals.

Code references:
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/DailyPnlCalculator.java` — daily-loss kill switch, the macro-level Kelly proxy.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderPlacer.java` — uses `riskPerTradePercent × equity ÷ riskPerUnit` to compute trade notional. Fixed risk, not Kelly.
- `frontend/src/components/portfolio/SettingsPanel.tsx` — exposes `riskPerTradePercent`, `maxConcurrentPositions`, `maxDailyLossPercent` as user-editable.

## Implementation sketch (when sample size justifies it)

Phase-in plan once we cross n = 1000 closed signals (likely Q3 2026):

1. Compute per-strategy `μ_R, σ_R, skew, kurtosis` from `signal_outcomes`.
2. Compute multi-position-adjusted Kelly: full Kelly fraction divided by `(1 + average_correlation × (max_positions − 1))`.
3. Apply fractional Kelly `k = 0.25` as a hard ceiling on the per-trade percent.
4. Cap the result at 2% (regulatory hygiene; never bet more than 2% of equity on any single trade regardless of what Kelly says).
5. Compare live PnL paths to fixed-1% sizing over a 90-day shadow window before going live.

Effort: ~3 days to implement the calculator, then 90 days of shadow data to validate.

## Sources

1. [Thorp, E. O. "Understanding the Kelly Criterion" (2008)](https://rybn.org/halloffame/PDFS/2008_Understanding_Kelly_New.pdf) — Thorp's own practitioner-facing exposition. Covers full vs fractional and the drawdown probabilities.
2. [Thorp, E. O. "The Kelly Criterion in Blackjack, Sports Betting, and the Stock Market"](https://gwern.net/doc/statistics/decision/2006-thorp.pdf) — Thorp's longer treatment with the stock-market generalization.
3. [Thorp, E. O. "The Kelly Criterion and the Stock Market"](http://www.edwardothorp.com/wp-content/uploads/2016/11/TheKellyCriterionAndTheStockMarket.pdf) — Thorp's PDF directly from his archive.
4. [MacLean, Thorp & Ziemba (eds.) *The Kelly Capital Growth Investment Criterion* (World Scientific, 2011)](https://www.amazon.com/KELLY-CAPITAL-GROWTH-INVESTMENT-CRITERION/dp/9814383139) — the definitive 1000-page anthology, theory and practice.
5. [Wikipedia, "Kelly Criterion"](https://en.wikipedia.org/wiki/Kelly_criterion) — clean reference for the formula and the discrete/continuous-time variants.
6. [MacLean, Ziemba & Blazenko, "Good and Bad Properties of the Kelly Criterion"](https://www.stat.berkeley.edu/~aldous/157/Papers/Good_Bad_Kelly.pdf) — explicit accounting of where Kelly helps and where it hurts. The drawdown-probability theorem is here.
7. [Frontiers in Applied Math, "Practical Implementation of the Kelly Criterion" (2020)](https://www.frontiersin.org/journals/applied-mathematics-and-statistics/articles/10.3389/fams.2020.577050/full) — recent practitioner-facing study on number-of-trades and rebalancing frequency effects.
