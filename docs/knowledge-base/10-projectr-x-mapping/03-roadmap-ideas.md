# Roadmap — Strategies Worth Considering Next

> Pre-registered hypotheses for new strategies + dimension improvements. Listed in rough order of empirical likelihood × implementation effort. Read this before adding a new detector — chances are someone (probably the prior version of you) already thought about it.

## Tier 1 — High likelihood, low effort

### A. Lower `alignmentFloor` 70 → 55
**Hypothesis**: the productive bucket per 14d empirical data is 50–70 (+35R total), not 70+ (+0.06R). Floor at 70 admits the flattest bucket and rejects the productive one.

**Effort**: settings PATCH (1 minute).

**Pre-registration**: success = signal→trade conversion rises from 5.9% toward 15–25% over the next 30d, AND cumulative R remains positive net of the increased volume.

**Failure mode**: if the post-v5 weight redistribution moved the productive bucket upward in alignment, we'd over-admit. Re-check the bucket distribution at v5+30d before tuning floor again.

**Status**: **awaiting user approval** — flagged in `04-empirical-findings.md`.

### B. Bump whale weight in next SignalConfig (v6 candidate)
**Hypothesis**: whale is the single best discriminator (+16.7 W−L diff, ahead of even technical at +15.7) yet only carries 0.25 weight. Bumping to 0.30 should improve the composite's separator power.

**Effort**: new SignalConfig version via existing REST or DB INSERT (5 minutes).

**Pre-registration**: success = win/loss diff on `overall_score` rises from +2.2 toward +5 in the next 30d, AND total R stays positive.

**Failure mode**: whale signal is regime-conditional; in BEAR/CHOP it may discriminate well but in BULL the cross-section narrows. Watch the regime mix.

**Status**: backlogged — need ≥30 closed signals on v5 first to baseline.

### C. ATR-relative MIN_RISK_PCT
**Hypothesis**: the absolute 1.5% MIN_RISK_PCT is correct for BTC-grade volatility but too tight for TRX (typical 45m vol ~0.05%) and too loose for ZEC (more volatile, false flooring). Use `max(2 × ATR(45m), 1.5%)` instead.

**Effort**: edit `SignalEngine.populateTradeLevels` + tests (~1 hour).

**Pre-registration**: success = TRX/ADA/XLM stagnation rate drops below 30%, BTC/ETH not affected.

**Failure mode**: ATR(45m) can spike on news bars and produce one-off insane risk values. Cap at, say, `min(2 × ATR(45m), 3.0%)`.

**Status**: design-stage.

### D-prime. Recalibrate BULL `strongBuy` threshold from data
**Hypothesis**: dimension-scoring has emitted **zero `STRONG_BUY` labels in 30 days** (53 BUY-side signals, max overall_score = 45.3, p90 = 42.1). BULL `strongBuy≥70` is mathematically unreachable for the dimension-scoring channel. Detectors (LS, TC) produce plenty of STRONG signals — but the overview-driven path has been carrying a dead label class.

**Evidence (30-day query against `signal_outcomes` where `strategy='dimension-scoring'`)**:
| Percentile | Score |
|---|---|
| p50 | 37.0 |
| p75 | 38.8 |
| p90 | 42.1 |
| p95 | 44.3 |
| max | 45.3 |

**Proposed**: drop BULL `strongBuy` to 45 (alignment≥70 stays) so the top ~5% of BUY signals get the STRONG label. Same calibration on BEAR's `strongSell≤−45`.

**Effort**: settings — push v7 SignalConfig with `labels.bull.strongBuyMinScore=45`.

**Pre-registration**: success = ≥1 STRONG_BUY label per week in BULL regime AND the new STRONG_BUYs perform statistically indistinguishably or better than BUYs (avg R ≥ BUY avg R) on 30+ post-v7 closed signals.

**Failure mode**: STRONG_BUYs perform worse than BUYs — meaning the label loses meaning. If so, revert and document that "dimension-scoring naturally caps out below STRONG."

**Status**: pre-registered — waiting for 30+ closed signals post-v6 (~2 weeks) to baseline whether v6's whale weight bump already shifted the distribution.

### D. Confluence window tightening (15 → 5 min)
**Hypothesis**: `DetectorConfluenceCheck` requires a `dimension-scoring` PENDING outcome in same symbol+direction within 15 min. This window is wide enough that "confluence" often catches an old, stale dimension signal that no longer reflects current alignment. 5 min would be tighter and likely block more TC trades — but the ones that pass would be higher-conviction.

**Effort**: settings PATCH.

**Pre-registration**: success = TC trade frequency falls 40-50%, but per-trade avg R rises by ≥30% (Sharpe-improving). Failure = both fall.

**Failure mode**: too few TC trades survive to maintain meaningful sample size.

**Status**: design-stage.

## Tier 2 — Moderate effort, moderate confidence

### E. Funding-rate dimension as a NEW score input
**Hypothesis**: extreme persistent funding (e.g. >0.05% / 8h sustained for >24h) indicates over-leveraged longs (positive funding) or shorts (negative). A 7th dimension scoring `+50` when funding extreme-long, `−50` when extreme-short.

**Effort**: derivatives-service already collects funding. New dimension scorer + add to SignalEngine composite + new SignalConfig column (~4–6 hours).

**Theory**: `02-strategies/08-funding-rate-arbitrage.md`, `06-derivatives/01-perp-funding-mechanics.md`.

**Pre-registration**: success = ≥30 signals with non-zero funding dim, and W−L separation on funding ≥+5.

**Failure mode**: funding lags price; by the time it's "extreme" the move has happened.

### F. Implement Bollinger-snap reversal as a 4th detector
**Hypothesis**: classic mean-reversion entry — price closes >2σ outside 20-period Bollinger Band, then reclaims the band within 3 bars. Long/short the reclaim. Distinct strategy class from LS (LS uses swing-level pierce; this uses statistical-band pierce).

**Effort**: new `BollingerReversalDetector` (~1 day).

**Theory**: `02-strategies/02-mean-reversion.md`, `03-technical-analysis/07-bollinger-bands.md`.

**Pre-registration**: success = ≥20 closed setups in 30d, avg R > +0.15 net.

**Failure mode**: crypto trends ride bands for extended periods (Bollinger band-walk). Reversal entry caught in extended trend = full stop. Need a regime gate (only fire in CHOP).

### G. Cross-exchange dislocation alerts
**Hypothesis**: occasional 30s+ price divergences between Bybit, Binance, OKX (≥0.3%) precede directional move on the lagging exchange. Detect on whale-service WebSocket cluster.

**Effort**: extend whale-service with cross-exchange price aggregation + alert publisher (~1 day).

**Theory**: `01-market-structure/05-cross-exchange-arbitrage.md`.

**Pre-registration**: alert-only first (no execution). Track if 1-min subsequent move ≥0.15% on lagging side in alert-direction ≥55% of cases.

**Failure mode**: dislocations are usually exchange-specific bid/ask spreads, not real signal. Likely high false-positive rate.

### H. Resolve options-service outcomes backfill
**Hypothesis**: `option_opportunities` table accumulates rows with `outcome_resolved_at IS NULL`. Resolving them (was the realized move ≥ breakeven?) unlocks the hit-rate strip on the `/options` page.

**Effort**: new scheduled job in options-service that for every opportunity at `expiry < now()`, fetches the underlying's price on that date and computes realized move. (~2–4 hours).

**Theory**: `06-derivatives/06-straddles-and-strangles.md`.

**Pre-registration**: success = hit-rate bucketing visible on `/options` after ≥10 resolved opportunities per bucket.

## Tier 3 — Higher effort, exploratory

### I. Meta-labeling layer over signals
**Hypothesis**: per López de Prado, train a binary classifier on `(features → 1 if R>0, else 0)` and use it as a confidence multiplier on signal sizing. Doesn't change signal generation; just sizes them.

**Effort**: collect 6 months of `signal_outcomes` first (currently have ~5 weeks). Then a Python sidecar service that periodically retrains a gradient-boosted classifier.

**Theory**: `04-quant-methods/02-ml-for-trading.md`.

**Pre-registration**: success = walk-forward Sharpe on the meta-labeled signals > unfiltered Sharpe by ≥30% over the same window.

**Failure mode**: standard ML failure modes — overfitting, label leakage, distribution shift between train and live.

### J. Walk-forward backtest tooling
**Hypothesis**: we have a `backtest-service` but it's underutilized. Build a CLI / UI for running parameter sweeps with proper purged k-fold + embargo.

**Effort**: ~3–5 days.

**Theory**: `04-quant-methods/05-overfitting-and-cv.md`, `04-quant-methods/07-walk-forward-analysis.md`.

**Pre-registration**: success = tool produces walk-forward Sharpe + deflated Sharpe for an existing strategy in <1 min.

### K. Macro overlay (BTC dominance, DXY)
**Hypothesis**: macro dimension is currently a weak input. Augment with BTC dominance percentile and inverse DXY change as 30%/30% inputs to the Macro score.

**Effort**: new feed in market-data-service for dominance + DXY (free from FRED).

**Theory**: `07-crypto-specific/03-altcoin-rotations.md`, `01-market-structure/`.

### K-prime. Portfolio UI polish batch (a11y + UX)

**Hypothesis**: the portfolio surface ships and works for mouse users but has known a11y + UX rough edges flagged during the Plan 3 review cycle. Bundling them into a single polish PR is more efficient than dribbling.

Items (originally in `techdebt/frontend/3-2-plan-3-deferred-polish.md`, archived 2026-06-06):

1. **A11y focus management** — `ExchangeSetupModal`, `WhyModal`, `FirstTimeAutoTradeModal`, `PositionRowMenu`, `SettingsPanel` need focus trap + `role="dialog"` + `aria-modal="true"` + `aria-labelledby`. `PositionRowMenu` needs `role="menu"` / `role="menuitem"`. ~2 hours.
2. **`alert()` → toast** — `ExchangeCard.handleCloseAtMarket` uses browser `alert()`. Reuse `components/dashboard/AlertToast.tsx`. ~30 min.
3. **`TradeChartModal` + `TradeLedger` `accountId` filter** — currently `console.log` stubs on the menu actions. Need `accountId?: number` prop to scope. ~2 hours.
4. **`PositionRowMenu` popover scroll-follow** — anchor rect read once during render; menu drifts if user scrolls. Add scroll listener + reposition. ~30 min.

**Status**: queued — apply when next touching portfolio surface.

**Shipped 2026-06-06 from this batch**:
- ✅ `SettingsPanel` / `ExchangeSetupModal` strict number parsing — `Number()` instead of `parseInt`/`parseFloat` rejects trailing-garbage values like `3abc`.
- ✅ `useExecutionStream` trailing 250ms debounce on WS-frame-triggered REST refreshes — prevents 4×N gateway hits per burst.

### L. Listing/delisting watch
**Hypothesis**: listings produce short-horizon pumps; delistings cause stale-data risk (we lost XMRUSDT to a frozen-kline scenario). Monitor `/v3/symbols` daily for diffs.

**Effort**: ~2 hours. Tied to a known techdebt item: `2-2-silent-delisting-detection-gap`.

**Theory**: `07-crypto-specific/07-exchange-listings-impact.md`.

## What's been considered and rejected

| Idea | Why rejected |
|---|---|
| HFT market-making | Latency we can't match |
| Selling vol (theta-decay) | Tail risk inappropriate for size |
| Pairs / cointegration | Crypto correlations regime-unstable, see Liu et al. SSRN 3389152 |
| Pure DL next-bar prediction | Not reproducible in any published research as profitable |
| Twitter sentiment scraping | Empirically zero discrimination in our 14d data |
| Trading the Order Book dimension | Alpha decay in seconds, not 1min; see Tigro Blanc / arxiv 2602.00776 |

## Decision protocol

Before promoting a Tier 2 or 3 idea to active development:

1. **Backtest** on existing data using the backtest-service.
2. **Pre-register** the success criterion in this doc (specific R-multiple, sample size, deflated Sharpe).
3. **Ship behind a flag**, default off.
4. **Run for ≥30 closed signals** before tuning further.
5. **Apply deflated Sharpe** to discount the iteration count — see `04-quant-methods/06-deflated-sharpe.md`.

If a Tier 1 idea fails its pre-registration, document the failure in this doc (don't delete the entry) so we don't re-litigate it in 3 months.
