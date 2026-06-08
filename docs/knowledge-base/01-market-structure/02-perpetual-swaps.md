# Perpetual Swaps

> The dominant crypto instrument. A future with no expiry, kept anchored to spot by a periodic funding payment between longs and shorts.

## Definition

A perpetual swap (or "perp") is a derivative contract that mimics a fixed-maturity future but never expires. Two design choices make this possible:

1. **Mark price** — settlement and liquidation calculations don't use the last trade price (which can be manipulated thin-book), they use a composite "mark price" derived from a spot index and the funding-rate basis.
2. **Funding rate** — a periodic payment exchanged between longs and shorts (not the exchange), with sign and magnitude proportional to the difference between perp price and the spot index. When perp trades above spot, funding is positive and longs pay shorts. When perp trades below spot, funding is negative and shorts pay longs.

These two mechanisms together produce a price that, in the absence of arbitrage frictions, equals spot. With frictions, perp prices oscillate around spot in a band whose width is bounded by trader willingness to pay or receive funding to hold a position.

## Mechanics — the Bybit case

We execute exclusively on Bybit V5 USDT-margined perpetuals, so Bybit's specific implementation is the relevant reference. The mechanics are nearly identical across CEX perps with minor parameter variations.

**Funding settlement happens every 8 hours** at 00:00, 08:00, and 16:00 UTC. The settlement amount per position is:

```
funding fee = position notional * funding rate
```

The funding rate itself is a TWAP over 8-hour windows of per-minute snapshots:

```
F = P + clamp(I - P, -0.05%, 0.05%)
```

where `P` is a premium index measuring perp-vs-index divergence at top of book, `I` is a 0.03% interest-rate component, and `clamp` keeps the deviation from the interest rate within ±5 bps. ([Bybit funding rate intro](https://www.bybit.com/en/help-center/article/Introduction-to-Funding-Rate); [Bybit funding fee calculation](https://www.bybit.com/en/help-center/article/Funding-fee-calculation))

Funding upper/lower caps are derived from initial and maintenance margin rates and are venue-specific. On BTCUSDT Bybit, the cap is approximately ±3% per 8h — extreme, never hit in normal markets but does cap blow-off scenarios.

## Why funding is a signal, not just a cost

In academic theory ([He, Manela, Ross, von Wachter — "Fundamentals of Perpetual Futures", arXiv 2212.06888](https://arxiv.org/abs/2212.06888)), the funding rate is the no-arbitrage device that pins perp to spot. In practice, three things make it interesting as a market microstructure signal:

1. **Funding lags positioning.** Funding is computed every minute and TWAP'd over 8h. By the time the rate moves, the positioning has already moved. So observed funding measures *recent past* leverage, not current — useful as a "where is the crowd now" gauge.
2. **Funding caps create reflexive crashes.** When funding hits its upper cap on a one-sided positioning, the marginal long has to pay an unsustainable rate to hold. The first deleveraging cascade is typically when funding peaks, not before.
3. **Funding ≠ basis.** The basis is the actual perp-spot price gap. Funding is the *integrator* of basis history. They diverge after sudden moves — a useful confirmation/divergence check.

Recent academic work refines this. [Designing funding rates for perpetual futures in cryptocurrency markets (arXiv 2506.08573)](https://arxiv.org/abs/2506.08573) shows that the conventional TWAP-based funding mechanism is path-dependent and can leave systematic gaps between perp and spot even at equilibrium. The paper proposes spot-tradable and non-tradable variants that close this gap analytically.

## What we do today

- **Funding rate is consumed by `derivatives-service`** as one of several signals fed into the `Derivatives` dimension score. Sourced from Binance Futures funding endpoint via `BinanceFuturesClient`, persisted to TimescaleDB.
- **Execution side** treats funding as a known cost, not a signal input. The `OutcomeEvaluator` includes a `fees_bps_round_trip` (default 10 bps, configurable per-outcome) that approximates fee drag. Funding accrual is *not* yet incorporated row-by-row, which is a known gap for trades held across funding events — tracked as a follow-up after v4.
- **Stops live on Bybit, not locally.** `BybitV5RestClient` places conditional/stop-market orders directly on the exchange, with `TrailMirror` ratcheting them via `setTradingStop` as the signal-side trail advances. This means stops survive our service restarts and are guaranteed to fire at Bybit's mark-price tick boundaries.

## When funding signals fail

- **Funding can stay high or negative for days during one-sided trend regimes** without reversing the trade. In Q4 2024 BTC pushed through multiple consecutive +0.05%/8h funding prints during the breakout — shorting the funding extreme there was catastrophic.
- **Around large unhedged option expiries** (monthlies, quarterlies), funding can spike on hedging flow without indicating speculative positioning.
- **Stablecoin de-pegs corrupt the index.** A USDT or USDC de-peg makes "spot index" denominator wrong and the resulting funding numbers nonsensical for hours.

## Reading list

1. [Bybit — Introduction to Funding Rate](https://www.bybit.com/en/help-center/article/Introduction-to-Funding-Rate) — canonical operator docs. Read this before reading anything else.
2. [Bybit — Funding Fee Calculation](https://www.bybit.com/en/help-center/article/Funding-fee-calculation) — exact formulas, settlement times, clamps.
3. [Bybit — Perpetual Futures Contract Fees Explained](https://www.bybit.com/en/help-center/article/Perpetual-Futures-Contract-Fees-Explained) — combine with `Funding Fee` to size total cost-of-carry.
4. [arXiv 2212.06888 — Fundamentals of Perpetual Futures (He, Manela, Ross, von Wachter)](https://arxiv.org/abs/2212.06888) — the rigorous theoretical treatment. No-arbitrage bounds, why convergence is *not* guaranteed in frictioned markets.
5. [arXiv 2506.08573 — Designing funding rates for perpetual futures in cryptocurrency markets](https://arxiv.org/abs/2506.08573) — recent paper proposing improved funding mechanisms; useful for understanding the limits of the standard TWAP approach.
6. [arXiv 2310.14973 — Reconciling Open Interest with Traded Volume in Perpetual Swaps](https://arxiv.org/pdf/2310.14973) — careful methodology for OI-vs-volume analysis, useful when interpreting the OI-derived component of the Derivatives dimension.
7. [arXiv 1912.03270 — BitMEX Funding Rate Correlation with Bitcoin Exchange Rate](https://arxiv.org/pdf/1912.03270) — older but useful historical baseline of how funding-vs-price correlation evolves.
