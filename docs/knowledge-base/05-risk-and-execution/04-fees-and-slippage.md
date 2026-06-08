# Fees and Slippage

> The two costs that turn backtest edges into live losses. If you don't model both, your strategy is a fantasy. For projectr-x on Bybit V5 the round-trip cost floor is ~11 bps; we encode it as 10 bps in the schema for the maker/taker mix we actually see.

## Definition

Two distinct costs eat returns on every executed trade:

**Fees** are the explicit, contractual amounts the venue charges. On Bybit V5 USDT-perpetual contracts (non-VIP standard tier as of 2026):

- **Maker fee: 0.0200%** (2 bps) — orders that rest on the book and add liquidity.
- **Taker fee: 0.0550%** (5.5 bps) — orders that cross the book and remove liquidity.

A round-trip taker → taker = 0.110% (11 bps). A round-trip maker → taker (or vice versa) = 0.075% (7.5 bps). Pure maker → maker = 0.040% (4 bps), but maker fills are not guaranteed and have queue risk. VIP tiers bring these down — at VIP 5+ the taker can drop to ~0.030% and the maker to 0.0% or even negative (rebate).

Bybit also charges **funding fees** every 8 hours on open perpetual positions. Funding is the perp-spot convergence mechanism; it can be positive or negative and is paid to/from counterparties on the opposite side. Funding is variable per-symbol per-hour and is not a "fee" in the explicit sense — it is a position-carry cost that shows up on the realized PnL line.

**Slippage** is the implicit cost from the price moving while your order executes. It has three components:

1. **Spread cost.** Even an instant taker order pays the half-spread to the opposite side of the book. Tight pairs like BTCUSDT have <1bp spread; thin alts can have 5–15bp spread.
2. **Market impact.** A large order moves the book against itself. Almgren & Chriss (2005) found this scales approximately as a power of trade size — they fit a **3/5 power law** to Citigroup's US equity execution data, contrary to the often-cited "square root law." The square-root law (impact ∝ √(size / ADV)) is a useful approximation; the 3/5 fit is more accurate empirically.
3. **Latency slippage.** From signal-fire to order-acknowledgment, prices move. For projectr-x's signal → execution pipeline this is sub-second but non-zero.

For positions sized at our typical 1% risk-per-trade on 13 liquid USDT pairs, the impact component is negligible (sizes are tiny relative to top-10 venue ADV). Spread + latency dominate, and they're hard to separate from each other in attribution.

## When it works (modeling correctly)

- **Fees are modeled per-leg, not as a round-trip.** Bybit charges entry and exit separately; if you batch them you can't distinguish "entry was a maker, exit was a market-stop taker" from "both were takers."
- **Slippage is included in the entry and exit price recorded.** `executed_trades.entry_price` and `closed_price` are the realized prices, not the intended ones. The system then computes R-multiples on realized prices — slippage automatically flows into realized R.
- **Funding is captured separately.** It is a continuous carry, not a per-trade fee. Trades open across a funding tick eat (or earn) it; the rest don't.

## When it fails (modeling wrongly)

- **Backtests use mid-quote prices.** Mid-quote ignores the half-spread you actually pay. A backtest at mid + 0 fees can show a Sharpe 2.5 strategy that's Sharpe 0.5 net. The AutoQuant paper (arXiv 2512.22476) demonstrates this empirically on BTC/ETH/SOL/AVAX perps: fee-only and zero-cost backtests materially overestimate annualized returns vs full-cost backtests under the same execution semantics.
- **Fee assumptions are tier-stale.** Computed at VIP 0 rates when you're trading at VIP 3, or vice versa. Read the venue's current fee schedule per-API-key from the venue itself, don't hard-code.
- **Slippage modeled as zero.** "We'll use limit orders so no slippage" — except limits don't always fill, and the trades that *do* fill are conditional on price coming to you, which is selection bias against you (the trades that fill are the worst ones).
- **Stop-loss slippage ignored.** A market stop-loss on a 2% gap-down crypto move can slip 0.5–1.5% past the intended price. R-multiples computed on the "intended" stop will overstate edge.
- **No fee in expectancy aggregation.** A signal layer that shows `+0.5R/signal` gross is `+0.43R/signal` net at 0.075R per fee — 14% lower. The compounding effect over a year is substantial.

## What we do today (in projectr-x)

We model fees and slippage in three places, with different granularities:

**1. R-multiple computation (`OutcomeEvaluator`).** Every `signal_outcomes` row stores `fees_bps_round_trip` (default 10 bps — slightly conservative vs Bybit's actual 11 bps taker/taker, accounting for the maker entries we sometimes get). The R-multiple is:

```
realized_r = (closed_price − entry_price) / risk_per_unit × direction_sign − feesInRUnits
```

where `feesInRUnits = (fees_bps_round_trip / 10000) / (risk_per_unit / entry_price)`. On a 1.5% risk distance, 10bps fees = 0.667% of R = 0.067R drag per trade. On the 14-day v4 sample (272 trades), total fee drag is `0.067R × 272 ≈ 18.2R`. The reported `+32R total` is net of this. Gross would be ~`+50R`. **The fee model is non-trivial; turning it off would dramatically inflate apparent edge.**

**2. Stop-distance floor (`SignalEngine.populateTradeLevels`).** `MIN_RISK_PCT = 1.5%` was widened from the pre-v4 `0.5%` specifically because at 0.5% risk distance, 11 bps round-trip fees were 22% of R — fee drag was destroying expectancy. The 1.5% floor brings fee drag down to ~7% of R per trade, a manageable cost.

**3. Realized prices everywhere on execution.** `trade-execution-service` records actual Bybit fill prices in `executed_trades.entry_price` and `closed_price`, not the requested levels. Slippage is automatically captured in the realized R-multiple — there is no "slippage column" because it doesn't need one; it's already in the price.

**What we do NOT model yet:**

- **Funding carry.** Open perpetual positions accrue funding every 8h. Bybit's closed-PnL endpoint includes funding in `cumExecFee` / `cumExecValue` totals, and our `OrderReconciler.closeFromReconcile` reads `realized_pnl_usdt` from those — so funding *is* captured in realized USDT PnL. It's just not broken out as a separate column. For 14-day-typical hold times of <2 hours, funding rarely fires more than once per trade, so the impact is small (typical 0.01% funding × at most 1 tick = 0.0001 = 1bp). For longer-hold strategies this would need explicit accounting.
- **Slippage attribution.** We don't separate spread from impact from latency. They're aggregated into the realized price.
- **Per-VIP-tier fee lookup.** `fees_bps_round_trip` is a column default (10), not a per-account lookup from Bybit's API. If we promote a real account to VIP 1, the column won't auto-update.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java` — `feesInRUnits` computation.
- `db/init/signal-init.sql` — `signal_outcomes.fees_bps_round_trip DEFAULT 10`.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java` — Bybit fee data flowing through closed-PnL responses.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderReconciler.java` — `closeFromReconcile` reads `realized_pnl_usdt` net of fees and funding.

## Implementation sketch (gaps to close)

1. **Per-VIP-tier fee lookup.** On account add, query Bybit `GET /v5/account/fee-rate` for the actual tier and persist into `ExchangeAccount.makerBps` / `takerBps`. Update `fees_bps_round_trip` on each `signal_outcomes` insert from those columns. Effort: ~1 day.
2. **Funding column.** Add `signal_outcomes.funding_paid_usdt` (default 0, populated from Bybit closed-PnL response). Long-hold strategies need this; short-hold doesn't. Effort: ~half a day.
3. **Slippage telemetry.** On every fill, log `(intended_price − fill_price) × direction_sign` to a `fill_slippage` log. Aggregate to a dashboard showing slippage distribution per symbol. Effort: ~2 days. Acceptance: detect any symbol where 95th-percentile slippage > 5bps.

## Sources

1. [Bybit Help Center, "Futures Contracts: Fees Explained"](https://www.bybit.com/en/help-center/article/Perpetual-Futures-Contract-Fees-Explained) — canonical maker/taker rates and the round-trip math for perpetuals.
2. [Bybit Trading Fees official page](https://www.bybit.com/en/announcement-info/fee-rate/) — current rate table, kept up to date by the venue itself.
3. [Bybit, "Trading Fee Structure"](https://www.bybit.com/en/help-center/article/Trading-Fee-Structure) — VIP tier discount breakdown.
4. [Almgren, R., Thum, C., Hauptmann, E., & Li, H. "Direct Estimation of Equity Market Impact" (2005)](https://www.cis.upenn.edu/~mkearns/finread/costestim.pdf) — Citigroup-equity-data study fitting a 3/5 power law for market impact, often discussed alongside the square-root law it tested against.
5. [arXiv 2512.22476 "AutoQuant: An Auditable Expert-System Framework for Execution-Constrained Auto-Tuning in Cryptocurrency Perpetual Futures"](https://arxiv.org/abs/2512.22476) — demonstrates that fee-only and zero-cost backtests materially overestimate annualized returns on BTC/ETH/SOL/AVAX perps vs fully-costed backtests. Required reading on the cost-realism case.
6. [Byditt, "Bybit Fee Guide 2026"](https://byditt.com/bybit-fee-guide-2026-official-rates-20-discount-calculator/) — 3rd-party confirmation of the 2026 fee schedule; useful as a sanity check that the official numbers haven't drifted.
7. [Bucci, Mastromatteo et al. "Impact is not just volatility" arXiv 1905.04569](https://arxiv.org/pdf/1905.04569) — modern treatment of square-root impact law and where it does/doesn't hold.
