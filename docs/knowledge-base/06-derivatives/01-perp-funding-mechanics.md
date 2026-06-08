# Perpetual Funding Mechanics (Bybit V5)

> Funding is the periodic payment that anchors a perpetual contract's mark price to the spot index. On Bybit V5, it is calculated from a clamped premium index plus an interest rate term, paid every 8 hours.

## Definition

A perpetual futures contract has no expiry, so there is no convergence mechanism analogous to dated futures rolling to spot. The market needs a different anchor. Funding is that anchor: a periodic cash transfer between longs and shorts, sized to make the perpetual mark price track the spot index.

On Bybit V5 USDT perpetuals — the contract class that `trade-execution-service` trades — the funding interval is 8 hours, paid at 00:00, 08:00, and 16:00 UTC. The formula combines two terms. The first is the **premium index** `P`, which measures how far the perp's mark price has drifted above (or below) the spot index over the funding interval:

```
P = [max(0, Impact_Bid − Index) − max(0, Index − Impact_Ask)] / Index
```

The second is a fixed **interest rate** `I`, which represents the cost of holding the quote-asset versus base-asset position. For USDT-margined contracts with an 8-hour interval, `I = 0.01%` per funding period (0.03% per day divided by three intervals). Bybit zeroes the interest term for select pairs (e.g. USDCUSDT) but it is non-zero for the 13 pairs we trade.

The full funding rate `F` is computed as:

```
F = clamp[ P + clamp(I − P, +0.05%, −0.05%), F_upper, F_lower ]
```

The inner clamp creates the "dead zone": when the premium and interest are close (`|I − P| < 0.05%`), funding equals `P + (I − P) = I`. When they diverge, the inner term pins to ±0.05% and `F = P ± 0.05%`. The outer clamp limits the per-interval funding to Bybit's published upper/lower bounds (typically ±0.5% for BTC/ETH, ±2% for tail-asset perps). Average premium index is computed as a TWAP across the 480 one-minute samples in the 8h window, weighted linearly so the freshest minute matters most.

The settlement value paid by longs to shorts (or vice versa) is `position_value × F`. A trader holding $10,000 of BTCUSDT-PERP through a +0.01% funding event pays $1 to the short side. Funding is paid only by accounts holding a position at the snapshot time — flipping flat one second before settlement skips the payment.

## When it works

Funding is informative as a sentiment indicator over **multi-hour to multi-day** horizons. Persistently positive funding (e.g. `> +0.03%` per 8h sustained for 24h+) signals that longs are paying shorts to maintain exposure — the market has too many leveraged longs and is structurally vulnerable to a flush. Conversely, persistent negative funding flags crowded shorts and squeeze risk. The signal is strongest when funding diverges from realized price action: price drifting sideways while funding climbs means longs are accumulating without reward, a classic late-cycle setup.

Funding is also the most reliable carry signal in crypto. Cash-and-carry strategies (long spot, short perp during high-funding regimes) capture the funding payment as yield. During the 2024 cycle peaks, BTC funding ran 30–80% annualized on multiple Tier-1 venues — Coinbase Custody desks reported nine-figure cash-and-carry books at those rates.

## When it fails

- **Short-horizon trades:** an 8h funding rate tells you nothing about the next 15-minute move. Using funding to time entries on the bar level is misuse of the indicator.
- **Capitulation flushes:** funding can stay extremely negative through a downtrend that keeps falling — "crowded shorts" doesn't mean "imminent squeeze," it means "eventual squeeze."
- **Manipulation on thin venues:** smaller exchanges' funding can be wash-traded into deceptive readings. Always cross-check funding against the venue with the deepest order book for that contract (usually Binance or Bybit).
- **Settlement-clock games:** large traders sometimes flip flat moments before funding settlement to avoid a punitive payment, then re-enter — distorting open-interest and funding-based positioning models around settlement times.

## What we do today (in projectr-x)

`derivatives-service` (port 31085) ingests funding rate history from Bybit V5 via the `Get Funding Rate History` endpoint and writes it to TimescaleDB. The data feeds the `Derivatives` dimension in `SignalEngine` — extreme funding contributes to BUY scores when negative (crowded shorts) and to SELL scores when positive (crowded longs).

Provider class: `services/derivatives-service/src/main/java/com/cryptoradar/derivatives/provider/BybitFundingProvider.java`.

`trade-execution-service` does not currently incorporate funding into the `GuardrailPolicy.evaluate` decision — open positions accrue or pay funding at whatever rate Bybit charges, and the realized PnL backfill in `OrderReconciler.closeFromReconcile` reads `closedPnl` net of fees but does not separately break out funding paid versus trading fees. This is acceptable for the current short-hold horizon (median hold time under 4h pre-v5; many trades close before a single funding event).

### Cross-venue funding arbitrage (out of scope today)

The same perpetual contract trades across multiple venues — BTCUSDT-PERP on Bybit, Binance, OKX, Bitget — each with its own funding rate. Arbitrageurs exploit funding-rate divergences by being long the cheaper-to-hold venue and short the more-expensive one. This is a real strategy executed by basis-trading desks, but it requires multi-venue connectivity, cross-margining or sub-accounts, and careful management of position-size limits per venue. It is not in scope for this project, which is single-venue (Bybit only) by deliberate scope.

A related but simpler signal: when funding on Bybit diverges materially from Binance for the same pair (say, Bybit +0.04% / Binance +0.01% on the same 8-hour cycle), the asymmetry usually reflects a positioning imbalance on the diverging venue — the larger funding-paying side is over-concentrated there. Watching cross-venue funding deltas is one of the cheaper, higher-quality positioning signals available in crypto, and it would be a reasonable future addition to the `Derivatives` dimension input set.

### Funding payment timing and PnL semantics

Bybit's funding settlement is **on the timestamp**, not "during the prior 8 hours." A trader who opens a position 1 minute before settlement pays the full funding amount, regardless of how briefly they held the position. A trader who closes 1 minute before settlement pays nothing. This produces measurable "funding-time" microstructure: order flow and price action distortions in the final 5 minutes before each settlement, often called the "funding-time game." Our short-horizon execution path is exposed to this (a signal firing 2 minutes before settlement could result in a position picking up funding cost it didn't expect), but the magnitude is small enough that we haven't built explicit timing logic to avoid it.

## Sources

1. **Bybit Funding Rate Formula (Help Center).** https://www.bybit.com/en/help-center/article/Introduction-to-Funding-Rate — Canonical formula, interest-rate convention per interval, the ±0.05% inner clamp.
2. **Bybit V5 API — Get Funding Rate History.** https://bybit-exchange.github.io/docs/v5/market/history-fund-rate — REST contract for the endpoint our derivatives-service polls.
3. **Bybit Funding Fee Calculation (Help Center).** https://www.bybit.com/en/help-center/article/Funding-fee-calculation — Worked example with position size × rate settlement math.
4. **Bybit V5 API — Premium Index Kline.** https://bybit-exchange.github.io/docs/api-explorer/v5/market/premium-index-kline — Raw premium-index series, useful for reproducing `P` outside Bybit's aggregation.
5. **Alexander, Heck, Kaeck (2022), "The role of binance in bitcoin volatility transmission."** https://arxiv.org/abs/2202.02583 — Empirical evidence on how perp funding leads spot price action on multi-hour horizons.
