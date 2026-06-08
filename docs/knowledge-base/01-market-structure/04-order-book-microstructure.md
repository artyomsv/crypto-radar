# Order Book Microstructure

> How limit order books work mechanically, why book-imbalance signals decay in seconds, and why projectr-x's `Order Book` dimension empirically carries near-zero discriminatory power.

## Definition

A **limit order book (LOB)** is the data structure every electronic exchange uses to match orders. Two sides:

- **Bid side** — buyers, sorted descending by price. The best bid is the highest price someone will pay.
- **Ask side** — sellers, sorted ascending. The best ask is the lowest price someone will accept.

The gap between best bid and best ask is the **spread**. For BTC/USDT on Bybit, the spread is typically 0.01-0.02 bps in normal regimes — sub-cent on a $100k underlying.

Each level holds a queue of resting limit orders at that price, ordered by arrival time (price-time priority on most CEXes). New incoming orders are matched against the opposite side if their price crosses the spread (**market order** or marketable limit order), otherwise they join the queue at their level (**limit order**).

## Maker vs taker

- **Taker** — incoming order that crosses the spread and consumes resting liquidity. Pays a higher fee. On Bybit V5 USDT-perps the taker fee is 0.055% per side at the default tier — 0.11% round-trip, which is the fee number baked into projectr-x's `MIN_RISK_PCT = 0.015` (1.5%) stop floor: a 0.5% stop would put ~22% of every 1R into fees, intolerable.
- **Maker** — order that rests on the book waiting to be hit. Pays a lower fee, sometimes negative (rebate). Bybit V5 maker fee is 0.02% at the default tier.

The maker/taker fee asymmetry is the economic mechanism by which exchanges incentivize liquidity provision. Market makers profit by repeatedly posting on both sides and capturing the spread plus the rebate, minus adverse-selection losses.

## Why book-imbalance signals decay in seconds

A naive read of an order book — "more volume on the bid than the ask, therefore price will rise" — is the foundational hypothesis behind **order book imbalance** (OBI) signals. OBI is real but extremely short-lived. The reason is structural:

1. **Visible depth is a tiny fraction of true intent.** Iceberg orders, hidden quantities, and dark pools mean what's visible at L1-L5 is the iceberg's tip. Strategic traders intentionally hide.
2. **Cancellations dominate.** On a liquid CEX perp like Binance BTC/USDT, **90%+ of resting orders are cancelled before execution**. The book is a sea of bluffs.
3. **Market makers re-quote at sub-millisecond latency.** Any imbalance visible to a retail observer over a 1-second window has already been traded away by HFT systems with co-located connections and microsecond loops.
4. **The signal direction is *momentum, not reversion***, on most short horizons. Buy pressure visible at the top of book predicts further upside, not a snapback. This is documented in [Tigro Blanc — Meta-Order Flow in Crypto Perps](https://medium.com/coinmonks/meta-order-flow-in-crypto-perps-catching-big-whale-6a127e2f70e8): BTC information coefficient ≈ 0.10 and t-stat up to 6.86 at short horizons, but raw alpha around 0.42 bps at 30 seconds — well below the 4-bps round-trip taker cost. **OBI signals are real and profitable at HFT scale; they are not profitable at any horizon a retail taker can capture.**

The most rigorous recent treatment is [arXiv 2602.00776 — Explainable Patterns in Cryptocurrency Microstructure (Bieganowski & Ślepaczuk, Jan 2026)](https://arxiv.org/abs/2602.00776), which uses 1-second L2 books from Binance Futures across BTC, LTC, ETC, ENJ, ROSE and shows stable SHAP-importance patterns for OBI, spread, and adverse-selection features. The paper validates tradability at the top-of-book taker level only on the largest names, and only with conservative cost assumptions.

For a slightly different angle, [arXiv 2506.05764 — Exploring Microstructural Dynamics in Cryptocurrency Limit Order Books](https://arxiv.org/html/2506.05764v2) argues that better feature engineering matters more than deeper neural architectures — the inputs (right OBI definition, right time windowing, right depth) carry the signal, not the model.

## Why projectr-x's `Order Book` dimension scores zero in v5

The `Order Book` dimension was built early in the project on a 30s polling cadence (not WebSocket L2 streaming) with 5-level depth. Empirically, over the 14-day windows analyzed pre-v4, the orderbook-derived score showed **near-zero correlation with outcome direction or magnitude**. This matches the literature: at the 30s polling rate we use, OBI signals have decayed to noise by the time we score on them. In v5 the dimension's weight in the overall signal is set to zero — the column still populates for diagnostic visibility but does not influence the scored decision.

This is not a bug in the dimension, it's a structural limitation: capturing OBI requires sub-second L2 streaming, hardware close to the exchange, and a much lower-latency execution path than `signal-service → trade-execution-service → Bybit REST` provides. None of these are within the project's near-term scope.

## What we do today

- **Orderbook data is polled at 30s from Binance** by `market-data-service` and stored as a snapshot for the `Order Book` dimension scorer. The result is computed but weighted to zero in v5.
- **No L2 streaming.** The trade-execution path uses the WebSocket position/order channels (`BybitV5WsClient`) for our *own* state, not for L2 market data inputs.
- **No microsecond microstructure signals.** The `LiquiditySweepDetector`'s sweep/reclaim logic operates on closed 4h bars — that's still microstructure-derived (wicks reflect intrabar liquidity hunts) but at a timescale where the signal hasn't decayed.

## When LOB analysis fails outright

- **At polling rates ≥ 1s on liquid majors.** Signal is gone.
- **In dark/hidden liquidity regimes.** Some Bybit MMs use post-only iceberg flags that mask 80%+ of resting size. A snapshot looks thin but is actually deep — and vice versa.
- **Around exchange-side latency spikes.** Binance/Bybit periodically hit 200-500ms gateway congestion during big news. Snapshots taken in these windows are stale by construction.
- **Across exchanges.** Naively aggregating book depth across Binance + Bybit + OKX double-counts MMs who quote everywhere with the same inventory. Aggregate "depth" is not additive.

## Reading list

1. [Medium / Coinmonks — Tigro Blanc, Meta-Order Flow in Crypto Perps](https://medium.com/coinmonks/meta-order-flow-in-crypto-perps-catching-big-whale-6a127e2f70e8) — careful empirical OBI analysis on OKX L2 data; the headline finding is that signal exists but is below realistic transaction costs for taker execution.
2. [arXiv 2602.00776 — Explainable Patterns in Cryptocurrency Microstructure](https://arxiv.org/abs/2602.00776) — recent SHAP-based study showing stable cross-asset OBI patterns at 1-second resolution on Binance Futures.
3. [arXiv 2506.05764 — Microstructural Dynamics in Crypto LOBs](https://arxiv.org/html/2506.05764v2) — argues feature engineering > model depth for LOB prediction.
4. [arXiv 2507.22712 — Order Book Filtration and Directional Signal Extraction at High Frequency](https://arxiv.org/html/2507.22712v1) — methods for separating signal from cancel/replace noise.
5. [Towards Data Science — Price Impact of Order Book Imbalance in Cryptocurrency Markets](https://towardsdatascience.com/price-impact-of-order-book-imbalance-in-cryptocurrency-markets-bf39695246f6/) — accessible primer with empirical price-impact curves.
6. [Cube Exchange — What is Order Book Imbalance](https://www.cube.exchange/what-is/order-book-imbalance) — clean operational definition.
7. [Crypto-toolbox/HFT-Orderbook on GitHub](https://github.com/Crypto-toolbox/HFT-Orderbook) — reference implementation of an HFT-grade order book in C; useful if you ever want to build a streaming LOB ingestor.
