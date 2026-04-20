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
- **Trade execution**: `trade-execution-service/` — Quarkus 3.17 service that mirrors signals to real Bybit V5 USDT-perpetual orders. Depends on `shared-trade-core`. Encrypts API credentials (AES-GCM), validates permissions (rejects withdraw-enabled keys), maintains stops natively on Bybit. Tables: `exchange_accounts`, `executed_trades`, `execution_events` (see `db/init/execution-init.sql`).

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
- **`service/OutcomeEvaluator.java`** — `@Scheduled(every="60s")`, walks 1m candles per `PENDING` `signal_outcomes` row. Records MFE/MAE (with timestamps), ratchets a trailing stop (per-row config, default 1R activation / 0.5R step / 0.5R offset), detects target/trail/initial-stop hits, sets `final_exit_reason`, computes realized R net of round-trip fees. Target-first-when-trail-active rule: if both target and trail-stop inside one bar, trail-side optimistic because trail was prior-bar-ratcheted.
- **`service/OutcomeTracker.java`** — persists outcomes on signal transitions or detector setup fires. Dedups per `(symbol, direction, strategy)`.
- **`detector/` package** — pluggable `TradeSetupDetector` interface. Current: `LiquiditySweepDetector`, `TrendContinuationDetector`. Each can carry its own `TrailConfig` via `TradeSetup`.
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

## Frontend

Key files under `frontend/src/`:

- `types/index.ts` — all shared TS interfaces. `TradingSignal.alignment`, `SignalOutcomeView.finalExitReason`, `SignalOverview.marketRegime`, `PerformanceReport.{byExitReason, byAlignmentBucket, currentRegime}`
- `components/dashboard/SignalDashboard.tsx` — header with `RegimeBadge`, alignment gauge, signal distribution, top opportunity
- `components/dashboard/TradeLedger.tsx` — outcomes ledger. Status column resolves to `TARGET` / `TRAIL` / `STOP` / `LOCK +NR` (open+armed) / `EXPIRED` / `OPEN` via `resolveStatus(outcome)`
- `components/dashboard/TradeChartModal.tsx` — chart overlay of all outcomes for a symbol
- `components/dashboard/AiAnalysisModal.tsx` — Gemini prompt context, renders returned analysis
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
- **Stops and targets**: minimum risk distance `MIN_RISK_PCT = 0.005` (0.5% of entry) enforced on BOTH `SignalEngine.populateTradeLevels` AND `LiquiditySweepDetector.buildSetup`. `MIN_RR = 2.0` floor on target (resistance-based targets take precedence when they're farther).
- **Trail policy**: defaults `(activationR=1.0, stepR=0.5, offsetR=0.5)` on every outcome via entity defaults. Per-strategy override flows through `TradeSetup.trailConfig`.
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

## Current engine baseline (as of 2026-04-20)

- 44 signal-service unit tests passing across `SignalEngineBiasTest`, `SignalEngineStopPlacementTest`, `SignalEngineRegimeTest`, `OutcomeEvaluatorTrailingTest`, `OutcomeEvaluatorTimingAndFeesTest`, `MarketRegimeServiceTest`, `LiquiditySweepDetectorTest`
- Regime classified `BULL` at deploy time
- Metrics snapshot (periodDays=7): winRate **24.4%**, avgR **+0.45**, totalR **+38.3R**, profitFactor **1.64**
- Symbol list: 13 pairs (`XMRUSDT` deliberately dropped — Binance delisted 2024-02-20, API still served frozen klines)

## Further reading

- `README.md` — high-level product description + feature list
- `devops/README.md` — k3s deployment, CNPG clusters, Barman backups
- `techdebt/*.md` — prioritized tech-debt log
- `.claude/rules/` under `~/.claude/` — personal conventions (port ranges, money handling, docker, secrets, git, observability, resilience, clean-code) that apply to every edit in this repo
