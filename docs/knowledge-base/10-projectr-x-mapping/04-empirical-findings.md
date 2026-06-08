# projectr-x Empirical Findings — 14-day Window Ending 2026-06-03

> What the engine actually did between 2026-05-20 and 2026-06-03, with statistical caveats, so future decisions are grounded in our own data rather than vibes or backtests against synthetic markets.

## Headline numbers

| Metric | Value | Context |
|---|---|---|
| Closed signals | 272 | All strategies, all symbols |
| Cumulative R (net of 10bps fees) | **+32.22R** | ≈+0.118R per signal expectancy |
| Target hit rate | **2.9%** (8/272) | Far below 33% breakeven at 2:1 RR |
| Trail-stop hit rate | **45.6%** (124/272) | Where the edge actually lives |
| Initial-stop hit rate | 34.9% (95/272) | The bleed |
| Stagnation exit rate | 16.5% (45/272) | Small consistent loss, variance reducer |
| Trades executed on Bybit (demo, account 297) | **16** | 5.9% conversion from signal to trade |

The engine has a small **positive expectancy**. The 5.9% conversion to actual trades — the user's "fewer trades" symptom — is gates-driven, not signal-quality-driven.

## Exit-reason breakdown (per OutcomeEvaluator)

| Exit reason | n | avg R | total R | avg hold |
|---|---|---|---|---|
| TARGET | 8 | **+4.56** | +36.50 | 1199 min (~20h) |
| TRAIL_STOP | 124 | +0.88 | +109.43 | 507 min (~8.4h) |
| INITIAL_STOP | 95 | −1.09 | −103.54 | 329 min (~5.5h) |
| STAGNATION | 45 | −0.23 | −10.18 | 45 min (by definition) |

**Without the trail mechanism, the engine would be at −77R** (target wins + initial stops + stagnations).
The v5 trail-mirror fix (which restored a previously inert trail system) is doing 100% of the heavy lifting.

## Alignment-bucket sweet spot (the inverse-correlation finding)

| Bucket | n | targets | trails | stops | stags | avg R | total R |
|---|---|---|---|---|---|---|---|
| 25–50 | 49 | 3 | 18 | 25 | 3 | −0.066 | −3.23 |
| **50–70** | **217** | **5** | **101** | **68** | **41** | **+0.165** | **+35.39** |
| 70–85 | 8 | 0 | 5 | 2 | 1 | +0.007 | +0.06 |
| 85+ | 0 | — | — | — | — | — | — |

`★ Insight ─────────────────────────────────────`
**The productive bucket is 50–70, not 70+.** This is the operational signal of the multicollinearity / crowded-trade phenomenon (see `02-strategies/04-momentum.md` and `04-quant-methods/04-feature-engineering.md`). When all 6 dimensions agree (alignment ≥70), the move has typically already happened. The current `executionSettings.alignmentFloor=70` is BLOCKING the productive bucket. Lowering to 55 would admit ~217 signals worth of edge while still excluding the slightly-negative 25–50 zone.
`─────────────────────────────────────────────────`

## Dimension discrimination power

Mean score for winning vs losing closed signals (last 14d, n=272):

| Dimension | Wins avg | Losses avg | **Diff (W−L)** | Verdict |
|---|---|---|---|---|
| **Whale** | 25.4 | 8.8 | **+16.7** | Strongest single discriminator |
| Technical | 90.0 | 74.3 | +15.7 | Strong |
| Derivatives | 6.7 | −2.1 | +8.8 | Moderate |
| Macro | −6.7 | −14.3 | +7.6 | Moderate |
| Sentiment | −0.1 | 0.1 | −0.1 | Noise (zeroed in v5) |
| Order-Book | 0.0 | 0.0 | 0.0 | Noise (zeroed in v5) |
| **overall_score (composite)** | 54.5 | 52.3 | **+2.2** | Composite drowns the signal |

The composite is the worst discriminator of all. This is the canonical "curse of dimensionality" outcome — noise dimensions dilute the discriminating ones. Action taken in v5 SignalConfig: Order-Book and Sentiment weights → 0; 0.2 weight redistributed proportionally to tech (→0.4375), whale (→0.25), deriv (→0.1875), macro (→0.125).

**Future v6 candidate**: bump whale weight further given it's the best single discriminator.

## Per-symbol pooled stats (14d, n per cell)

| Symbol | n | mean R | std R | total R | 95% CI low | CI high | Verdict |
|---|---|---|---|---|---|---|---|
| BCHUSDT | 18 | 0.702 | 1.882 | +12.64 | −0.17 | +1.57 | n < 30 |
| XLMUSDT | 18 | 0.619 | 1.954 | +11.14 | −0.28 | +1.52 | n < 30 |
| DOGEUSDT | 23 | 0.310 | 1.476 | +7.14 | −0.29 | +0.91 | n < 30 |
| LTCUSDT | 22 | 0.323 | 1.441 | +7.11 | −0.28 | +0.93 | n < 30 |
| XRPUSDT | 13 | 0.294 | 1.647 | +3.82 | −0.60 | +1.19 | n < 30 |
| LINKUSDT | 19 | 0.153 | 1.149 | +2.91 | −0.36 | +0.67 | n < 30 |
| ETHUSDT | 30 | 0.086 | 0.988 | +2.58 | −0.27 | +0.44 | **Indistinguishable from 0** |
| ADAUSDT | 25 | 0.094 | 1.151 | +2.34 | −0.36 | +0.55 | n < 30 |
| TRXUSDT | 21 | 0.020 | 0.972 | +0.41 | −0.40 | +0.44 | n < 30 |
| SOLUSDT | 21 | −0.101 | 0.937 | −2.11 | −0.50 | +0.30 | n < 30 |
| BTCUSDT | 37 | −0.119 | 0.833 | −4.41 | −0.39 | +0.15 | **Indistinguishable from 0** |
| BNBUSDT | 8 | −0.545 | 1.103 | −4.36 | −1.31 | +0.22 | n < 30 |
| ZECUSDT | 17 | −0.411 | 0.875 | −6.99 | −0.83 | +0.005 | n < 30 |

**No symbol has a CI that excludes zero** at 95% level. The point estimates are suggestive but not yet statistically defensible. See `04-quant-methods/05-overfitting-and-cv.md` for why per-cell samples below n=30 are anecdote, not edge. The `SymbolPerformanceGate` (active threshold=−3R) is operating on insufficient samples — a known limitation we accept for now in exchange for fail-open semantics.

## Per-(symbol × direction × strategy) top + bottom

**Top performers:**

| Cell | n | total R | Pattern |
|---|---|---|---|
| BCH SHORT TC | 16 | +12.76 | Most consistent winner |
| LTC SHORT TC | 22 | +7.11 | High-frequency, modest avg |
| XLM LONG TC | 11 | +7.04 | Counter-regime LONG worked |
| DOGE SHORT TC | 21 | +5.27 | Right-tail captures |
| XRP SHORT TC | 13 | +3.82 | |

**Bottom performers:**

| Cell | n | total R | Pattern |
|---|---|---|---|
| BTC SHORT TC | 23 | **−5.09** | 9 stagnations — vol mismatch with abs stagnation threshold |
| TRX LONG TC | 17 | **−3.59** | **11 stagnations** of 17 — low-vol symbol drowning in stagnation rule |
| ZEC SHORT TC | 5 | −3.65 | n=5 — anecdote |
| SOL SHORT TC | 21 | −2.11 | Marginal |
| BNB LONG TC | 2 | −2.12 | n=2 — anecdote |

**Pattern:** SHORT trend-continuation on mid-cap alts dominates; BTC SHORTs lose; TRX bleeds via stagnation. The TRX-stagnation issue was the direct driver of the ATR-scaled stagnation rule shipped 2026-06-03 (see `05-risk-and-execution/03-trailing-stops.md`).

## Execution funnel (before 2026-06-03 instrumentation)

Of 272 signals → only 16 reached Bybit (5.9% conversion). The other 256 were filtered:

| Stage | Approximate share | Now observable? |
|---|---|---|
| `SignalSubscriber.isBelowAlignmentFloor` (floor=70) | ~94% (all alignment 50–70 admitted by data but blocked by floor) | ✅ post-2026-06-03 |
| `SymbolPerformanceGate.isSuppressed` (−3R threshold) | Suppressed BTC SHORT TC, ZEC SHORT TC, TRX LONG TC | ✅ post-2026-06-03 |
| `DetectorConfluenceCheck` (trend-continuation requires open dimension-scoring outcome) | Affected most TC signals in low-confluence regimes | ✅ post-2026-06-03 |
| `DailyPnlCalculator` (max_daily_loss_percent=10%) | **0 triggers ever** | already observable |
| Dedup (existing open position) | 7 events in 14d (0.5% of total) | already observable |

After 2026-06-03, every gate writes a `SIGNAL_BLOCKED_*` event (coalesced to one per gate × symbol × direction × 60s). One day of post-deploy data will quantify the precise funnel.

## Strategy comparison (when n ≥ 10)

| Strategy | n | total R | avg R | Note |
|---|---|---|---|---|
| trend-continuation | ~240 | +28R | +0.12 | Workhorse |
| liquidity-sweep | ~24 | +5R | +0.20 | Lower frequency, higher per-trade R |
| dimension-scoring | ~10 | −2R | −0.20 | Underperforms TC and LS; both losers when alignment is high |

The dimension-scoring strategy fires only on overview-driven actionable transitions and has the WORST per-trade outcomes. This is the operational evidence behind the "high alignment underperforms" finding above — dimension-scoring requires alignment ≥ 55 (per current SignalConfig).

## Statistical caveats

1. **14 days is one regime**, not a representative population. Current regime was CHOP/BEAR.
2. **Per-cell n < 30** for 11 of 13 symbols. Bootstrap CIs straddle zero everywhere.
3. **Multiple-testing inflation** — we've iterated config at least 5 times in 30 days. Per Bailey/López de Prado deflated-Sharpe, the apparent edge should be discounted. See `04-quant-methods/06-deflated-sharpe.md`.
4. **Trail mirror was broken pre-v5** — the dataset above mixes pre-v5 (trail inert) and post-v5 (trail working) periods. Post-v5 trail contribution is artificially high in cumulative figures.
5. **Execution-side n=16 is too small** for any conclusion. The 16 trades had win rate 68.75% by USDT but the Bybit-side R-multiples differ from signal-side because of fill mechanics.

## Action items derived from this data (all shipped 2026-06-03 unless noted)

| Action | Doc reference | Status |
|---|---|---|
| Add per-gate rejection events (instrumentation) | `04-quant-methods/05-overfitting-and-cv.md` (observe before tune) | ✅ shipped |
| ATR-scale stagnation thresholds (kill TRX bleed) | `05-risk-and-execution/03-trailing-stops.md`, López de Prado triple-barrier | ✅ shipped |
| Zero Order-Book + Sentiment weights | `04-quant-methods/04-feature-engineering.md` | ✅ shipped |
| Widen TC trail offset 0.5R → 0.75R | `05-risk-and-execution/03-trailing-stops.md` | ✅ shipped |
| Lower `alignmentFloor` 70 → 55 | This doc + `05-risk-and-execution/02-r-multiples-and-expectancy.md` | ✅ shipped 2026-06-06 (v6) |
| Bump whale weight (v6 SignalConfig) | `04-quant-methods/04-feature-engineering.md` | ✅ shipped 2026-06-06 — whale 0.25 → 0.35, tech 0.4375 → 0.35, deriv 0.1875 → 0.20, macro 0.125 → 0.10 |
| Tighten confluence window 15 → 7 min | `10-projectr-x-mapping/03-roadmap-ideas.md` (Tier 1D) | ✅ shipped 2026-06-06 (v6) |
| Daily PnL halt 10% → 5% | `05-risk-and-execution/05-leverage-and-liquidation.md` | ✅ shipped 2026-06-06 (v6) |
| `StrategyPerformanceSizer` per-cell 0.5–1.5× | `05-risk-and-execution/01-position-sizing-kelly.md` | ✅ shipped 2026-06-06 (v6) — fail-open, `NOT_SUPPORTED` tx isolation |
| `/api/execution/analytics/funnel` + `/strategy-pnl` | `04-quant-methods/05-overfitting-and-cv.md` | ✅ shipped 2026-06-06 (v6) |
| BollingerReversalDetector (CHOP regime) | `02-strategies/02-mean-reversion.md` | 📅 deferred to dedicated session — needs backtest design |
| Symbol-perf-gate threshold review (pooled bootstrap-CI) | `04-quant-methods/06-deflated-sharpe.md` | 📅 backlogged — needs ≥30 closed signals/cell |

## Refresh cadence

This doc should be re-run roughly every 30 days (≥30 closed signals on the latest config to clear CLT) OR after any deployment marker. Numbers above are frozen to the 2026-05-20 → 2026-06-03 window.

## Sources

1. **Deflated Sharpe Ratio** — Bailey & López de Prado, SSRN 2460551. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551 — used as the rationale for the multiple-testing caveat.
2. **Crowded trades** — Stein 2009 / Macrosynergy summary. https://macrosynergy.com/research/crowded-trades-and-consequences/ — operational explanation of the inverse alignment finding.
3. **Triple-barrier method** — López de Prado / Hudson & Thames write-up. https://hudsonthames.org/does-meta-labeling-add-to-signal-efficacy-triple-barrier-method/ — basis for vol-scaling stagnation barriers.
4. **AutoQuant — costed backtests** — arxiv 2512.22476. https://arxiv.org/pdf/2512.22476 — fee-aware framing of the edge size.
5. Project deployment markers (`deployment_markers` table) — internal source of truth for what changed when.
