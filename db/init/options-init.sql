-- =============================================================================
-- Options watchlist service — per-contract market snapshots + opportunity log
-- =============================================================================
-- Stores Bybit options market data for short-horizon (0-3d) strangle/straddle
-- decisions. Three time-series tables:
--   1. option_snapshots — every-60s ticker dump of every contract we track
--   2. option_historical_vol — Bybit's published HV reading, joined with our
--      internally-computed realized vol from candles
--   3. option_opportunities — scored entries flagged by OpportunityScorer
--      (machine-readable for future auto-quote bot)
-- =============================================================================

-- =====================================================================
-- option_snapshots — high-volume time series, ~1M rows/day
-- =====================================================================
CREATE TABLE IF NOT EXISTS option_snapshots (
    time            TIMESTAMPTZ      NOT NULL,
    underlying      VARCHAR(16)      NOT NULL,
    symbol          VARCHAR(48)      NOT NULL,
    expiry          DATE             NOT NULL,
    strike          DOUBLE PRECISION NOT NULL,
    option_type     CHAR(1)          NOT NULL,
    bid             DOUBLE PRECISION,
    ask             DOUBLE PRECISION,
    mark            DOUBLE PRECISION,
    implied_vol     DOUBLE PRECISION,
    delta           DOUBLE PRECISION,
    gamma           DOUBLE PRECISION,
    theta           DOUBLE PRECISION,
    vega            DOUBLE PRECISION,
    open_interest   DOUBLE PRECISION,
    volume_24h      DOUBLE PRECISION,
    underlying_px   DOUBLE PRECISION
);

SELECT create_hypertable('option_snapshots', 'time', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS idx_opt_snap_underlying_expiry_time
    ON option_snapshots (underlying, expiry, time DESC);
CREATE INDEX IF NOT EXISTS idx_opt_snap_symbol_time
    ON option_snapshots (symbol, time DESC);

-- Segment by (underlying, expiry) — queries always scope to one chain.
ALTER TABLE option_snapshots SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'underlying, expiry',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('option_snapshots', INTERVAL '3 days', if_not_exists => TRUE);
SELECT add_retention_policy('option_snapshots', INTERVAL '90 days', if_not_exists => TRUE);

-- =====================================================================
-- option_historical_vol — low-volume, exchange-published HV
-- =====================================================================
CREATE TABLE IF NOT EXISTS option_historical_vol (
    time            TIMESTAMPTZ      NOT NULL,
    underlying      VARCHAR(16)      NOT NULL,
    period_days     INT              NOT NULL,
    hv              DOUBLE PRECISION NOT NULL,
    UNIQUE(time, underlying, period_days)
);

SELECT create_hypertable('option_historical_vol', 'time', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS idx_opt_hv_underlying_time
    ON option_historical_vol (underlying, period_days, time DESC);

-- =====================================================================
-- option_opportunities — scorer output, NOT a hypertable (low volume,
-- queried by id and recent detected_at). Keeps a row per flagged setup.
-- =====================================================================
CREATE TABLE IF NOT EXISTS option_opportunities (
    id                BIGSERIAL PRIMARY KEY,
    detected_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    underlying        VARCHAR(16)      NOT NULL,
    expiry            DATE             NOT NULL,
    strike_call       DOUBLE PRECISION NOT NULL,
    strike_put        DOUBLE PRECISION NOT NULL,
    call_symbol       VARCHAR(48)      NOT NULL,
    put_symbol        VARCHAR(48)      NOT NULL,
    strangle_premium  DOUBLE PRECISION NOT NULL,
    implied_vol_atm   DOUBLE PRECISION,
    realized_vol_7d   DOUBLE PRECISION,
    realized_vol_14d  DOUBLE PRECISION,
    iv_rv_spread      DOUBLE PRECISION,
    signal_overlay    DOUBLE PRECISION,
    confidence        DOUBLE PRECISION NOT NULL,
    metadata          JSONB,
    -- Ground-truth fields filled later by a backfill job once expiry passes:
    realized_move_pct DOUBLE PRECISION,
    outcome_pnl_pct   DOUBLE PRECISION,
    outcome_resolved_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_opp_underlying_detected
    ON option_opportunities (underlying, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_opp_open
    ON option_opportunities (outcome_resolved_at NULLS FIRST, detected_at DESC);

-- =====================================================================
-- crypto_assets seed — add XAUT and MNT for inventory/UI consistency,
-- but mark INACTIVE. options-service reads its tracked underlyings from
-- the `options.underlyings` env var directly, not from crypto_assets.
-- Setting is_active=true would cause market-data-service and
-- derivatives-service to try polling these on Binance — Binance does
-- not list them, producing a steady stream of HTTP 400 errors.
-- =====================================================================
INSERT INTO crypto_assets (symbol, name, rank, is_active) VALUES
    ('XAUTUSDT', 'Tether Gold',  9001, false),
    ('MNTUSDT',  'Mantle',       9002, false)
ON CONFLICT (symbol) DO NOTHING;
