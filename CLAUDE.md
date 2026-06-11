# CLAUDE.md — projectr-x

Project memory for future Claude sessions. Start here before making changes.

## Stack

- **Backend**: Java 21, Quarkus 3.17, Panache (Hibernate ORM), RESTEasy Reactive, smallrye-health, WebSocket, Scheduled tasks, Jackson
- **Frontend**: React 19, TypeScript, Vite 6, Tailwind CSS, TradingView Lightweight Charts
- **Data**: TimescaleDB 2.17.2 on PG16 (hypertables + 7d compression), PostgreSQL 16-alpine (news only), Redis 7-alpine (pub/sub)
- **Infra**: Docker Compose for local; Kustomize + CloudNativePG + Barman Cloud for k3s (`devops/`)
- **CI/local tools**: `mvnd` (Maven daemon) for fast iterative test runs
- **Build chain**: `pom.xml` → Quarkus uber-jar → Dockerfile COPY into runtime image
- **Shared Java module**: `shared-trade-core/` — pure-JAR Maven module, no framework deps. Holds `TrailCalculator`, `TrailConfig`, `RUnitMath`. Installed into local `.m2` via `mvn install`; consumed by `signal-service` (outcome evaluator's trail math) and the upcoming `trade-execution-service`.
- **Trade execution**: `trade-execution-service/` — Quarkus 3.17 service that mirrors signals to real Bybit V5 USDT-perpetual orders. Depends on `shared-trade-core`. Encrypts API credentials (AES-GCM), validates permissions (rejects withdraw-enabled keys), maintains stops natively on Bybit. Tables: `exchange_accounts`, `executed_trades`, `execution_events` (see `db/init/execution-init.sql`). Shares the TimescaleDB database with signal-service — `SymbolPerformanceGate` and `DetectorConfluenceCheck` issue read-only native queries against `signal_outcomes`.

## Services + host ports

Per `~/.claude/rules/local-port-ranges.md` — all host-exposed ports in `31xxx`.

| Service | Host | Internal | Purpose |
|---|---|---|---|
| frontend | 31000 | 80 | React dashboard via nginx |
| api-gateway | 31080 | 8080 | REST aggregation + WebSocket |
| market-data-service | 31081 | 8081 | Binance prices/candles + backfill |
| news-service | 31082 | 8082 | CoinDesk + RSS + sentiment |
| analytics-service | 31083 | 8083 | Technical indicators + macro |
| whale-service | 31084 | 8084 | 6-exchange WebSocket streams |
| derivatives-service | 31085 | 8085 | Funding, OI, long/short, liquidations |
| signal-service | 31086 | 8086 | Dimension scoring + detectors + outcome tracker |
| trade-execution-service | 31087 | 8087 | Bybit V5 execution, trail-mirror, reconciler |
| timescaledb | 31432 | 5432 | Time-series hypertables |
| postgres | 31433 | 5432 | News/metadata |
| redis | 31379 | 6379 | Pub/sub |

## signal-service — the analytic heart

Files of note (under `services/signal-service/src/main/java/com/cryptoradar/signal/`):

- **`service/SignalEngine.java`** — 6-dimension scorer. Computes alignment (0-95, formerly "confidence" — renamed because outcome analysis showed an inverse correlation with win rate). `determineSignalLabel(score, alignment, regime)` applies regime-adjusted thresholds: BULL raises SELL bar, BEAR raises BUY bar, CHOP/UNKNOWN use PR3 transitional defaults.
- **`service/MarketRegimeService.java`** — classifies BTC as BULL / BEAR / CHOP / UNKNOWN from 60× 1d candles, 50-day SMA + 7-day slope, 2% band. Refresh every 15 min via `@Scheduled`, primes on `StartupEvent`. Regime feeds SignalEngine and surfaces in `SignalOverview.marketRegime`.
- **`service/OutcomeEvaluator.java`** — `@Scheduled(every="60s")`, walks 1m candles per `PENDING` `signal_outcomes` row. Records MFE/MAE (with timestamps), ratchets a trailing stop (per-row config, default 1R activation / 0.5R step / 0.5R offset, widens to 1.0R offset at MFE ≥ 2.5R via `TrailConfig.widerOffsetActivationR`), detects target/trail/initial-stop hits, sets `final_exit_reason`, computes realized R net of round-trip fees. Also runs a stagnation check after the bar loop: if age ≥ 45 min AND MFE < 0.2% AND MAE > −0.3%, closes the outcome with `final_exit_reason='STAGNATION'` at the last bar's close. Target-first-when-trail-active rule: if both target and trail-stop inside one bar, trail-side optimistic because trail was prior-bar-ratcheted.
- **`service/OutcomeTracker.java`** — persists outcomes on signal transitions or detector setup fires. Dedups per `(symbol, direction, strategy)`.
- **`detector/` package** — pluggable `TradeSetupDetector` interface. Current: `LiquiditySweepDetector`, `TrendContinuationDetector`, `DonchianBreakoutDetector` (`donchian`, 20/10), `TurtleSystem1Detector` (`turtle-s1`, 20/10 + loser-filter), `TurtleSystem2Detector` (`turtle-s2`, 55/20). The three breakout detectors read a daily `DonchianSnapshot` injected into `MarketContext` by `DonchianChannelService` (60×1d candles, cached 1h); breakout/N math lives in `shared-trade-core` `DonchianMath`. Live execution + pyramiding is Plan 2 (not yet built). Toggle each via `turtle.{donchian,s1,s2}.enabled`.
- **`repository/SignalOutcomeRepository.java`** — Panache repo. Queries used: `findPending()`, `findOpenByStrategy(symbol, direction, strategy)`, `findFiredSince(Instant)`, `findRecent(limit)`, `findRecentBySymbol(symbol, limit)`.
- **`resource/SignalResource.java`** — REST endpoints (all prefixed `/api/signals/`).

### Key endpoints (all under `/api/signals/`)

- `GET /overview` — current snapshot: strong-buy/buy/neutral/sell/strong-sell counts, `marketBias`, `marketRegime`, `topOpportunity`, full signals array
- `GET /{symbol}` — single-symbol `TradingSignal`
- `GET /{symbol}/raw-data` — every input that fed the symbol's last signal
- `POST /{symbol}/ai-analysis` — on-demand Gemini analysis
- `GET /metrics?periodDays=30` — aggregate performance report
- `GET /outcomes?symbol=X&limit=N` — recent outcomes ledger
- `GET /deployments` — engine-change markers for slicing metrics before/after

### signal_outcomes schema — the feedback backbone

TimescaleDB hypertable on `fired_at` (defined in `db/init/signal-init.sql`). Columns:

- Core: `fired_at`, `signal_id`, `symbol`, `strategy`, `signal_type`, `direction`
- Trade levels: `entry_price`, `stop_price`, `target_price`, `risk_reward_ratio`, `alignment`
- Dimension snapshot: `technical_score`, `whale_score`, `derivatives_score`, `sentiment_score`, `orderbook_score`, `macro_score`, `overall_score`
- Lifecycle: `status`, `closed_at`, `closed_price`, `realized_pnl_pct`, `realized_r_multiple`
- Excursions: `max_favorable_pct`, `max_adverse_pct`, `time_to_mfe_seconds`, `time_to_mae_seconds`
- Trailing stop: `trail_activation_r`, `trail_step_r`, `trail_offset_r`, `trail_highest_r`, `dynamic_stop_price`, `trail_triggered_at`, `final_exit_reason`
- Fees: `fees_bps_round_trip` (default 10)
- `ai_analysis TEXT` — the Gemini verdict, if fetched

Secondary indexes: `(status, fired_at DESC)` for evaluator scan, `(symbol, direction, strategy, status)` for dedup, `(symbol, fired_at DESC)` for metrics. Compression at 30d on `(symbol, signal_type)`.

### deployment_markers table

Append-only `(deployed_at PRIMARY KEY, version, description)`. Consumers join on `signal_outcomes.fired_at` ranges. Three rows seeded to date:

| deployed_at | version |
|---|---|
| 2026-04-19 20:00 UTC | `v1-initial-fixes` — bias removal, stop-distance guard, LS filter tightening, MIN_RR 5→2 |
| 2026-04-19 23:30 UTC | `v2-trail-system` — trailing stop ladder, final_exit_reason, time_to_mfe/mae, fees |
| 2026-04-20 01:00 UTC | `v3-full-rollout` — regime detection, LS volume, confidence→alignment, metrics slices |
| 2026-04-24 00:00 UTC | `v4-data-driven-vectors` — G.1 derivatives unit fix, G.2 news sentiment feed, G.3 orderbook name fix, Vectors A/B/D execution gates, Vector E stagnation exit (exchange side default-off), Vector F trail second-rung at 2.5R |
| 2026-06-03 20:12 UTC | `v5-instrumentation-and-atr` — per-gate `SIGNAL_BLOCKED_*` events (60s-coalesced), ATR-scaled stagnation (`MFE<0.25×ATR(45m)`, `MAE>−0.4×ATR(45m)`, absolute fallback), SignalConfig v5 zeroes Order-Book + Sentiment weights, TC trail offset 0.5R → 0.75R |
| 2026-06-06 14:01 UTC | `v6-profitability-pass` — `alignmentFloor` 70 → 55 (unlocks empirically-productive 50–70 bucket worth +35R/14d), SignalConfig v6 whale 0.25 → 0.35 (best single discriminator W−L diff +16.7), confluence window 15 → 7 min, `max_daily_loss_percent` 10 → 5, `StrategyPerformanceSizer` per-cell 0.5–1.5x sizing, `/api/execution/analytics/funnel` + `/strategy-pnl` endpoints |

## Frontend

Key files under `frontend/src/`:

- `types/index.ts` — all shared TS interfaces. `TradingSignal.alignment`, `SignalOutcomeView.finalExitReason`, `SignalOverview.marketRegime`, `PerformanceReport.{byExitReason, byAlignmentBucket, currentRegime}`
- `components/dashboard/SignalDashboard.tsx` — header with `RegimeBadge`, alignment gauge, signal distribution, top opportunity
- `components/dashboard/TradeLedger.tsx` — outcomes ledger. Status column resolves to `TARGET` / `TRAIL` / `STOP` / `LOCK +NR` (open+armed) / `EXPIRED` / `OPEN` via `resolveStatus(outcome)`
- `components/dashboard/TradeChartModal.tsx` — chart overlay of all outcomes for a symbol
- `components/dashboard/AiAnalysisModal.tsx` — Gemini prompt context, renders returned analysis
- `components/portfolio/` — `ExchangeAccountsSection` (stacked exchange cards below Manual), `ExchangeCard` (per-account header + equity + positions + recent), `ExchangeCardHeader`, `EquitySummary` (5-card strip), `OpenPositionsTable` (detector badges + trail indicator + ⋯ menu), `PositionRowMenu` (View chart / Why / Close at market), `WhyModal`, `ExchangeSetupModal` (single-step form), `AddExchangeButton` (dashed CTA empty state), `FirstTimeAutoTradeModal` (localStorage-gated per account), `KillSwitchBanner`, `SettingsPanel` (slide-in right with 7 PATCHable fields), `ConnectionIndicator` (green/amber-pulsing/red status dot + staleness)
- `hooks/useExecutionStream.ts` — WS to `/ws/execution` + 15s REST polling fallback + staleness counter
- `hooks/useExecutionAccounts.ts` — list/create/patch/delete wrappers over `/api/execution/accounts`
- `lib/api.ts` — typed fetch wrappers for `/api/signals/*` endpoints

## Local dev commands

```bash
# Start everything (first time / after code changes)
docker compose up -d --build

# Rebuild one service + restart
docker compose build signal-service && docker compose up -d --no-deps signal-service

# Tail one service
docker compose logs signal-service -f

# Run signal-service tests (Java local, mvnd is on PATH)
cd services/signal-service && mvnd test

# Quick TimescaleDB poke
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c "SELECT ..."
```

## Conventions (project-specific)

- **Money & R-multiples**: realized R is **net of fees** (see `OutcomeEvaluator.feesInRUnits`). `realized_pnl_pct` stays gross. Both stored per-row.
- **Stops and targets**: minimum risk distance `MIN_RISK_PCT = 0.015` (1.5% of entry, widened from 0.5% in v4 to cut Bybit 0.11% round-trip fee drag) enforced on BOTH `SignalEngine.populateTradeLevels` AND `LiquiditySweepDetector.buildSetup`. `MIN_RR = 2.0` floor on target (resistance-based targets take precedence when they're farther).
- **Trail policy (v4)**: `TrailConfig.DEFAULT = (activationR=1.0, stepR=0.5, offsetR=0.5, widerOffsetActivationR=2.5, widerOffsetR=1.0)` — two-rung ladder. Trail activates at MFE≥1R with tight 0.5R offset; once MFE≥2.5R the offset widens to 1.0R so right-tail runners have more room before the trail takes them. Legacy 3-arg constructor still works and disables the second rung. Per-strategy override flows through `TradeSetup.trailConfig`.
- **Dimension names**: the scorer emits `"Order Book"` (with space), not `"OrderBook"`. Any lookup against `MarketContext.dimensionScores()` or the `OutcomeTracker` dimension switch must use the spaced form — typo caused `orderbook_score` to stay NULL in all historical outcomes pre-v4.
- **Outcome tracking dedup**: by `(symbol, direction, strategy)`. Multiple detectors can hold open outcomes for the same symbol+direction simultaneously — different trade ideas, measured independently.
- **Engine thresholds** in `SignalEngine.determineSignalLabel`:
  - CHOP / UNKNOWN: `strongBuy≥55, buy≥25, strongSell≤-40, sell≤-15` (PR3 transitional — SELL side looser than BUY side until bias-fixes are validated on ≥2 weeks of fresh data)
  - BULL: SELL side reverts to symmetric `strongSell≤-55, sell≤-30` (counter-trend needs stronger evidence)
  - BEAR: BUY side tightened to `strongBuy≥70, buy≥40` (don't catch falling knives)
- **LS detector filters** (`LiquiditySweepDetector`):
  - `MIN_PIERCE_ATR_FRACTION = 0.3` (quarter-ATR pierce required)
  - `MIN_RECLAIM_BODY_RATIO = 0.3` (close must reclaim ≥30% of body)
  - `MIN_ATR_PCT = 0.003` (skip low-ATR regimes)
  - `MAX_DRIFT_PCT = 0.5` (entry within 0.5% of trigger close)
  - `DIM_DERIVATIVES_TOLERANCE = 5.0` (deriv can't oppose reversal by more than this)
  - `MIN_VOLUME_RATIO = 1.3` against prior 3 bars
  - `STOP_BUFFER_ATR = 0.5` (widened from 0.2 after MAE analysis)
- **`alignment` is the honest name for what the scoring stack computes.** Higher alignment ≠ higher probability of winning (inverse correlation observed pre-fix; hypothesis TBD on post-fix data). Never conflate the two in analysis/UX copy.

## Secrets

- Local dev: `.env` (gitignored), template in `.env.example`. Keys: `WHALE_ALERT_API_KEY`, `GEMINI_API_KEY`, DB creds.
- k3s: no secrets in git. CNPG auto-generates DB credentials at bootstrap. API keys live in a `Secret` created out-of-band from `devops/overlays/dev/secrets.example.yaml`. See `devops/README.md`.

## Tech debt tracking

Live in `techdebt/` (see `~/.claude/rules/tech-debt-tracking.md` for format). High-priority items as of last review:

- `2-2-missing-tests-api-gateway-proxy-resource`
- `2-2-missing-tests-outcome-tracker-and-evaluator` (trail + evaluator logic still untested at unit level)
- `2-2-missing-unit-tests-bybit-okx-providers`
- `2-2-missing-unit-tests-signal-engine-gemini-service`
- `2-2-silent-delisting-detection-gap` (XMRUSDT-style stale-kline risk)

## Current engine baseline (as of 2026-04-24, v4 shipped)

- 55 signal-service tests, 111 trade-execution-service tests, 38 shared-trade-core tests passing
- Regime classified `BULL` at deploy time
- Phase 2 outcome slice (2026-04-20 → 2026-04-23, pre-v4): winRate 45.9%, avgR **−0.135**, totalR −14.95 — inverted Derivatives scorer held SELL signals mathematically unreachable, zero SHORT outcomes ever fired
- Post-v4 immediate effect: LTC Derivatives swung from stuck `+35` to honest `−35`; three symbols (ETH, XRP, LTC) sit within 3–10 pts of their first-ever SELL signal
- Symbol list: 13 pairs (`XMRUSDT` deliberately dropped — Binance delisted 2024-02-20, API still served frozen klines)

## Execution gates (trade-execution-service, added in v4)

All live in `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/` or `.../lifecycle/`. Each is configurable via env vars and has a kill-switch for instant rollback.

- **`SignalSubscriber.isBelowAlignmentFloor`** — rejects overview-envelope signals with `alignment < execution.alignment.floor` (default 70). Detector alerts lack alignment and bypass. Phase-2 rationale: the 40–55 alignment bucket lost 8.87R across 19 trades.
- **`SymbolPerformanceGate`** — reads last N closed outcomes per symbol from `signal_outcomes`; suppresses dispatch when cumulative R ≤ threshold. Defaults: `lookback=10`, `threshold-r=-3.0`, `cache-ttl=30s`. Self-healing as the rolling window ages out.
- **`DetectorConfluenceCheck`** — trend-continuation entries (BOTH LONG and SHORT) require an open `dimension-scoring` outcome in the same symbol+direction within `execution.confluence.window-minutes` (default 15). v4 originally gated only LONGs (Vector B); after the SHORT-gap was unblocked the first 4 v4 SHORTs all lost, so the rule was mirrored symmetrically. Other detectors unaffected. Toggle: `execution.confluence.trend-continuation.required` (default true).
- **`DailyPnlCalculator`** — computes today's realized PnL since UTC midnight as a percent of cached Bybit equity, fed into `GuardrailPolicy.evaluate` as the daily-halt input. Equity cache TTL: `execution.daily-pnl.equity-cache-ttl-sec` (default 60s). Trip threshold lives on `ExchangeAccount.maxDailyLossPercent` (default 7%). Open-position unrealized PnL is intentionally excluded — the gate fires on realized losses only to avoid oscillation. Fail-open when wallet endpoint fails.
- **`StagnationMonitor`** (lifecycle, `@Scheduled`) — exchange-side companion to the OutcomeEvaluator stagnation exit. Reads MFE/MAE from `signal_outcomes` via the trade's `signal_id` and calls `OrderPlacer.close(..., ExitReason.STAGNATION)` when stagnant. **Default disabled** (`execution.stagnation-monitor.enabled=false`). Flip to `true` only after tracking-side STAGNATION reasons have been observed for 24–48h.

All gates fail-open on query errors — a stuck read cannot block legitimate trades (or, for the monitor, force-close live positions).

## Trail mirror — v5 critical fix

Before v5, the entire v2/v3/v4 trail system was silently inert in production: zero of 35 closed trades had `trail_triggered_at` set or `trail_highest_r > 0` despite signal-side `OutcomeEvaluator` ratcheting trails on `signal_outcomes`. Root cause: `MarketDataClient.getLastPrice` parsed the wrong response shape — expected an object keyed by symbol, but `/api/market/prices` returns an ARRAY of `{symbol, price, ...}`. Every lookup returned null, `TrailMirror.processTrade` early-returned at every tick, dynamic stops never moved. Fix: array-walk parse in `MarketDataClient.parsePrice` (extracted method for unit-testability). Going forward, v4 trail wins should start materializing in `executed_trades.trail_triggered_at` once trades reach the +1R activation threshold.

## Trade close pipeline (single-source v5)

The path from "Bybit closed the position" → "row in `executed_trades` has correct PnL/fees/exit_reason/R-multiple" was previously split between WS `BybitV5WsClient.handlePosition` (fast, but only set status=CLOSED) and the periodic `OrderReconciler` (filled the rest). The split caused two bugs: (a) the WS race left rows permanently null because the reconciler skips already-CLOSED rows; (b) the reconciler defaulted `exit_reason` to TARGET when missing, mislabeling 26 of 28 v3-era stop hits as wins.

v5 collapses to one canonical close path in `OrderReconciler.closeFromReconcile`:

- **Called from both** the periodic reconcile loop AND the WS position handler (size→0 path) so close metadata is captured on the first signal of position closure.
- **Inferred `exit_reason`** via `inferExitReason(trade, ClosedPnlV5)` — compares `avgExitPrice` to `stop_price` / `target_price` (with 0.1% slippage tolerance) and uses the trail-active flag; falls back to PnL sign + trail flag when levels are missing. Defaults to `MANUAL` rather than `TARGET` when the row truly cannot be classified.
- **Backfills `entry_price`** from the closed-pnl payload's `avgEntryPrice` so R-multiple math survives missed WS execution events. `OrderPlacer` also pre-fills the intended entry up front; the WS first-fill event refines it (using `lastSyncAt == null` as the "haven't seen any WS event yet" marker).
- **Time-windowed match** in `pickMatchingClose` — only accepts a closed-pnl entry whose `createdTime` falls between `openedAt − 1h` and `closedAt + 24h`. Without this, repeat trades on the same symbol could steal each other's PnL payload (caused mis-backfills with stop_price on the wrong side of entry_price).
- **Idempotent** — calling `closeFromReconcile` on a row already CLOSED only fills NULL fields. Returns `true` only when at least one field changed.

Two admin endpoints repair pre-fix data without resetting the DB:
- `POST /api/execution/accounts/{id}/admin/backfill-closes?limit=N` — re-fetches closed-pnl from Bybit for any CLOSED row missing `realized_pnl_usdt`, `exit_reason`, or `entry_price`. Bounded by Bybit's ~30-day closed-pnl history window.
- `POST /api/execution/accounts/{id}/admin/repair-exit-reasons?limit=N` — re-classifies rows where `exit_reason` contradicts the recorded PnL sign (TARGET with negative pnl, INITIAL_STOP with positive pnl). Pure local re-inference; no Bybit call.

`computeRMultiple` rejects rows with corrupt risk geometry (stop on the wrong side of entry, or risk distance < 0.1% of entry) so backfill artifacts cannot pollute aggregate metrics.

## Further reading

- `README.md` — high-level product description + feature list
- `devops/README.md` — k3s deployment, CNPG clusters, Barman backups
- `techdebt/*.md` — prioritized tech-debt log
- **`docs/knowledge-base/`** — opinionated, cited trading/markets KB. Start there for strategy theory, risk frameworks, derivatives mechanics, and the projectr-x ↔ theory crosswalk (`docs/knowledge-base/10-projectr-x-mapping/`). Read `docs/knowledge-base/README.md` first.
- `docs/signal-config-api.md` — REST API for signal config versioning + reload
- `.claude/rules/` under `~/.claude/` — personal conventions (port ranges, money handling, docker, secrets, git, observability, resilience, clean-code) that apply to every edit in this repo
