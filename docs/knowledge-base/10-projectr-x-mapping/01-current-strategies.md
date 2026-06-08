# Current Strategies — Live Inventory

> What's running in production right now, what each strategy is doing, where the code lives. Sibling to `02-implemented-detectors.md` (low-level math) — this one is the operator's view.

## Service-by-service strategy surface

| Service | Strategies | Status |
|---|---|---|
| signal-service | dimension-scoring, liquidity-sweep, trend-continuation | Live, all firing |
| trade-execution-service | mirrors signals 1:1 to Bybit V5 perpetuals | Live, account 297 DEMO |
| options-service | long-vol (strangle/straddle) when IV << RV | Live, no resolved outcomes yet |
| whale-service | on-chain flow ingestion (input, not a strategy) | Live, feeds Whale dimension |
| derivatives-service | funding / OI / L-S ratio / liquidations ingest | Live, feeds Derivatives dimension |
| news-service | sentiment ingestion | Live but Sentiment dim now zero-weighted |

## Strategy 1 — Dimension-scoring (the engine)

**What it does.** Every ~5s, scores 6 dimensions per symbol (technical/whale/derivatives/macro + zero'd OB/sentiment), computes a weighted overall_score, and labels each symbol BUY / STRONG_BUY / NEUTRAL / SELL / STRONG_SELL based on regime-aware thresholds.

**When it fires.** Continuously. The label may stay NEUTRAL for hours during low-conviction regimes — that's correct behavior, not silence.

**Trade levels.** Stop = `max(ATR×multiple, support distance × buffer, entry × 1.5%)`. Target = `max(entry + 2R, structural resistance)`.

**Empirical** (14d): ~10 closed signals, net −2R. *Underperforms* TC and LS by a lot. This is the operational evidence behind the "high-alignment underperforms" finding.

**Theory**: `02-strategies/04-momentum.md`, `04-quant-methods/04-feature-engineering.md`.

## Strategy 2 — Liquidity-sweep reversal

**What it does.** Detects pierce-and-reclaim bars at swing levels (stop-hunt geometry). Fires when:
1. Bar wicks below a recent swing low by ≥0.3 × ATR
2. Close reclaims ≥30% of the bar back into range
3. Volume ≥ 1.3 × avg of prior 3 bars
4. Entry within 0.5% of trigger close
5. Derivatives dimension not opposing by >5 points

**When it fires.** Rare. ~24 firings in 14d. Higher per-trade R than other strategies (+0.20 avg vs +0.12 engine-wide).

**Trade levels.** Stop = swing ± 0.5 × ATR. Target = max(entry + 5R, structural level).

**Theory**: `02-strategies/03-liquidity-sweep-and-reversal.md`.

## Strategy 3 — Trend-continuation

**What it does.** Fires on healthy 0.3–2.0% pullbacks to SMA20 in established HTF trends (SMA50 vs SMA200 alignment), with RSI in 35–65 (no overbought tops, no panic bottoms), and cross-dimension confluence.

**When it fires.** Most frequent strategy. ~240 firings in 14d. The workhorse.

**Trade levels.** Stop = 1.5 × ATR. Target = max(entry + 5R, support/resistance). **Trail offset 0.75R** (wider than other strategies — preserves the right tail per 2026-06-03 calibration).

**Empirical pattern**: SHORT TC on mid-cap alts (BCH/LTC/XLM/DOGE) is the engine's most consistent winning surface. BTC SHORT TC and TRX LONG TC are the loss centers (both gated by SymbolPerformanceGate).

**Theory**: `02-strategies/01-trend-following.md`.

## Strategy 4 — Long-volatility options (NEW, options-service)

**What it does.** Scans Bybit options chain for setups where ATM implied vol is materially below realized vol. Publishes opportunity rows when scorer confidence ≥75 (current threshold).

**Mechanism.** When IV<<RV, the market is pricing options too cheaply relative to historical movement. A long straddle/strangle profits whenever realized vol exceeds the breakeven move.

**Status.** Live since 2026-05. **No resolved outcomes yet** — opportunities are persisted but the resolution backfill job that fills `realized_move_pct` / `outcome_pnl_pct` has not run. We do NOT currently execute these — they're alert-only.

**Theory**: `06-derivatives/03-implied-vs-realized-vol.md`, `06-derivatives/06-straddles-and-strangles.md`.

## What we explicitly do NOT do

These are listed so future contributors know they're deliberate omissions, not oversights:

| Strategy | Why not |
|---|---|
| **Short volatility** (selling straddles/strangles) | Unbounded loss tail. Inappropriate for a small account. See `06-derivatives/06-straddles-and-strangles.md`. |
| **Pure market-making** (limit-order spreads) | Requires sub-100ms latency we can't match. Capital better deployed in directional signals. See `02-strategies/06-market-making.md`. |
| **Cash-and-carry funding-rate arb** | Margin tied up in spot side reduces capital efficiency. Worth revisiting if funding goes >20% APR sustained. See `02-strategies/08-funding-rate-arbitrage.md`. |
| **Cross-exchange arbitrage** | Latency- and capital-constrained. Possible at small scale on rare dislocations but not our edge. See `01-market-structure/05-cross-exchange-arbitrage.md`. |
| **Pairs trading / statarb** | Crypto correlation structure is regime-unstable. Cointegrating windows blow out without warning. See `02-strategies/05-statistical-arbitrage.md`. |
| **Pure ML / deep-learning prediction** | We score dimensions and let the engine compose them. Black-box DL on next-bar prediction has not produced reliable edges in our or published research. See `08-prediction-frameworks/05-deep-learning-llms.md`. |

## How a new strategy gets added

1. Read `02-strategies/` to confirm the strategy class isn't already a known failure mode.
2. Read `09-sources/02-papers.md` for academic baseline.
3. Implement as a `TradeSetupDetector` in `services/signal-service/.../detector/`.
4. Wire into `SignalEngine` setup-detection loop (no separate registration; CDI auto-discovers).
5. **Pre-register success criteria** in `03-roadmap-ideas.md` before measurement.
6. Ship behind a `@ConfigProperty` toggle (default off).
7. Let it accumulate ≥30 closed signals before drawing conclusions (see `04-quant-methods/05-overfitting-and-cv.md`).
8. Add a row to this doc and to `02-implemented-detectors.md`.

## See also

- `02-implemented-detectors.md` — low-level math + file:line refs
- `03-roadmap-ideas.md` — what's next
- `04-empirical-findings.md` — the data behind every claim above
