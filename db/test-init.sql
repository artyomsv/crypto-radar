-- Test-DB bootstrap for trade-execution-service @QuarkusTest runs.
--
-- The integration tests target a dedicated `marketdata_test` database on
-- the same TimescaleDB container as the live `marketdata`. The test
-- schema must mirror the live schema closely enough for JPA queries +
-- native cross-table reads (SymbolPerformanceGate, StrategyPerformanceSizer,
-- DetectorConfluenceCheck, ObservabilityResource) to execute without
-- `relation does not exist` or `column does not exist` errors.
--
-- This file is idempotent — re-runnable. Apply with:
--   docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata_test -f /tmp/test-init.sql
-- or via the helper described in services/trade-execution-service/README.md.
--
-- IMPORTANT: this file does NOT replace db/init/execution-init.sql or
-- db/init/signal-init.sql for the live DB; it is a minimal mirror for
-- test runs only. Keep it in sync when columns are added/removed in the
-- live init scripts that the tests actually read from.

-- 1. execution_settings — Telegram fields added in 2026-05 must be present.
ALTER TABLE IF EXISTS execution_settings
    ADD COLUMN IF NOT EXISTS telegram_enabled         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE IF EXISTS execution_settings
    ADD COLUMN IF NOT EXISTS telegram_bot_token_enc   TEXT;
ALTER TABLE IF EXISTS execution_settings
    ADD COLUMN IF NOT EXISTS telegram_chat_id         TEXT;
ALTER TABLE IF EXISTS execution_settings
    ADD COLUMN IF NOT EXISTS telegram_notified_events TEXT;
ALTER TABLE IF EXISTS execution_settings
    ADD COLUMN IF NOT EXISTS telegram_notify_options  BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. signal_outcomes — minimal table so that
--    SymbolPerformanceGate.queryTotalR and StrategyPerformanceSizer.queryStats
--    native queries return zero rows instead of "relation does not exist".
CREATE TABLE IF NOT EXISTS signal_outcomes (
    fired_at            TIMESTAMPTZ      NOT NULL,
    signal_id           VARCHAR(64)      NOT NULL,
    symbol              VARCHAR(20)      NOT NULL,
    strategy            VARCHAR(32)      NOT NULL DEFAULT 'dimension-scoring',
    signal_type         VARCHAR(16)      NOT NULL,
    direction           VARCHAR(8)       NOT NULL,
    entry_price         DOUBLE PRECISION NOT NULL,
    stop_price          DOUBLE PRECISION NOT NULL,
    target_price        DOUBLE PRECISION NOT NULL,
    risk_reward_ratio   DOUBLE PRECISION NOT NULL,
    alignment           INTEGER          NOT NULL,
    overall_score       DOUBLE PRECISION NOT NULL,
    technical_score     DOUBLE PRECISION,
    whale_score         DOUBLE PRECISION,
    derivatives_score   DOUBLE PRECISION,
    sentiment_score     DOUBLE PRECISION,
    orderbook_score     DOUBLE PRECISION,
    macro_score         DOUBLE PRECISION,
    ai_analysis         TEXT,
    status              VARCHAR(16)      NOT NULL DEFAULT 'PENDING',
    closed_at           TIMESTAMPTZ,
    closed_price        DOUBLE PRECISION,
    realized_pnl_pct    DOUBLE PRECISION,
    realized_r_multiple DOUBLE PRECISION,
    max_favorable_pct   DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_adverse_pct     DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_evaluated_at   TIMESTAMPTZ,
    final_exit_reason   VARCHAR(32),
    PRIMARY KEY (fired_at, signal_id)
);
