-- Signal outcome tracking (runs on TimescaleDB)
-- Records every actionable signal fired by the signal engine so we can
-- measure, after the fact, whether it hit its target or stop. This is the
-- feedback loop that turns blind signal generation into measurable edge.

CREATE TABLE IF NOT EXISTS signal_outcomes (
    fired_at            TIMESTAMPTZ      NOT NULL,
    signal_id           VARCHAR(64)      NOT NULL,
    symbol              VARCHAR(20)      NOT NULL,
    strategy            VARCHAR(32)      NOT NULL DEFAULT 'dimension-scoring',
    signal_type         VARCHAR(16)      NOT NULL,   -- BUY / STRONG_BUY / SELL / STRONG_SELL (or detector verdict)
    direction           VARCHAR(8)       NOT NULL,   -- LONG / SHORT
    entry_price         DOUBLE PRECISION NOT NULL,
    stop_price          DOUBLE PRECISION NOT NULL,
    target_price        DOUBLE PRECISION NOT NULL,
    risk_reward_ratio   DOUBLE PRECISION NOT NULL,
    confidence          INTEGER          NOT NULL,
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
    PRIMARY KEY (fired_at, signal_id)
);

-- Time-partitioned so the evaluation scan over PENDING rows stays cheap.
SELECT create_hypertable('signal_outcomes', 'fired_at', if_not_exists => TRUE);

-- Scan target #1: the evaluator walks all PENDING rows every minute.
CREATE INDEX IF NOT EXISTS idx_signal_outcomes_status_fired
    ON signal_outcomes (status, fired_at DESC);

-- Scan target #2: dedup check ("is there already an open outcome for this symbol+direction+strategy?").
-- Each strategy tracks its own trades independently so multi-detector fires don't collide.
CREATE INDEX IF NOT EXISTS idx_signal_outcomes_dedup
    ON signal_outcomes (symbol, direction, strategy, status);

-- Scan target #3: metrics endpoint filters by fired_at range.
CREATE INDEX IF NOT EXISTS idx_signal_outcomes_symbol_fired
    ON signal_outcomes (symbol, fired_at DESC);

-- Compression for historical analysis once rows are well past their resolution window.
ALTER TABLE signal_outcomes SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol,signal_type',
    timescaledb.compress_orderby   = 'fired_at DESC'
);

SELECT add_compression_policy('signal_outcomes', INTERVAL '30 days', if_not_exists => TRUE);

-- =============================================================================
-- Trailing-stop columns (PR2)
-- =============================================================================
-- Each outcome carries its own trail config so per-strategy calibration stays
-- auditable. Defaults match the winning simulation parameters: activation at
-- 1R MFE, step 0.5R, offset 0.5R (stop trails 0.5R behind MFE peak).
--
-- dynamic_stop_price is updated by the evaluator as MFE climbs. When a candle
-- crosses it, final_exit_reason = TRAIL_STOP (vs INITIAL_STOP / TARGET / EXPIRED).
-- Existing PENDING rows adopt the defaults via ALTER … DEFAULT and continue
-- tracking on the next evaluator tick.

ALTER TABLE signal_outcomes
    ADD COLUMN IF NOT EXISTS trail_activation_r DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS trail_step_r       DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS trail_offset_r     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS trail_highest_r    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS dynamic_stop_price DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS trail_triggered_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS final_exit_reason  VARCHAR(16);
    -- final_exit_reason values: INITIAL_STOP | TRAIL_STOP | TARGET | EXPIRED
