-- Trade execution service — schema for Bybit (and future exchanges) mirroring
-- of signal-service trading signals to real orders.
--
-- Three tables, none are hypertables (low volume, point-lookup queries dominate).

-- =====================================================================
-- exchange_accounts — one row per (exchange, environment). Encrypted key
-- material + per-account settings (risk %, leverage, guardrails).
-- =====================================================================

CREATE TABLE IF NOT EXISTS exchange_accounts (
    id                          BIGSERIAL PRIMARY KEY,
    exchange                    VARCHAR(32)   NOT NULL,
    environment                 VARCHAR(16)   NOT NULL,
    api_key_encrypted           TEXT          NOT NULL,
    api_secret_encrypted        TEXT          NOT NULL,
    label                       VARCHAR(64),
    auto_trade_enabled          BOOLEAN       NOT NULL DEFAULT false,
    kill_switch                 BOOLEAN       NOT NULL DEFAULT true,
    risk_percent                NUMERIC(5,2)  NOT NULL DEFAULT 1.0,
    default_leverage            INT           NOT NULL DEFAULT 3,
    max_concurrent_positions    INT           NOT NULL DEFAULT 5,
    max_daily_loss_percent      NUMERIC(5,2)  NOT NULL DEFAULT 5.0,
    signal_age_seconds          INT           NOT NULL DEFAULT 60,
    position_max_age_hours      INT           NOT NULL DEFAULT 24,
    flip_persistence_ticks      INT           NOT NULL DEFAULT 2,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT exchange_accounts_exchange_env_uq UNIQUE (exchange, environment),
    CONSTRAINT exchange_accounts_env_ck
        CHECK (environment IN ('DEMO', 'MAINNET')),
    CONSTRAINT exchange_accounts_risk_pct_ck
        CHECK (risk_percent > 0 AND risk_percent <= 100),
    CONSTRAINT exchange_accounts_leverage_ck
        CHECK (default_leverage >= 1 AND default_leverage <= 50)
);

-- =====================================================================
-- executed_trades — one row per real position. Links signal_id back to
-- signal_outcomes for the "why" join. See spec for source-of-truth labels
-- per field (OURS vs Bybit).
-- =====================================================================

CREATE TABLE IF NOT EXISTS executed_trades (
    id                        BIGSERIAL PRIMARY KEY,
    exchange_account_id       BIGINT NOT NULL REFERENCES exchange_accounts(id) ON DELETE RESTRICT,
    signal_id                 VARCHAR(64),
    symbol                    VARCHAR(32) NOT NULL,
    direction                 VARCHAR(8)  NOT NULL,
    strategy                  VARCHAR(64),
    exchange_order_id         VARCHAR(64),
    exchange_order_link_id    VARCHAR(64),
    exchange_position_idx     INT,
    status                    VARCHAR(24) NOT NULL,
    entry_price               NUMERIC(20,8),
    qty                       NUMERIC(20,8),
    leverage                  INT,
    stop_price                NUMERIC(20,8),
    target_price              NUMERIC(20,8),
    dynamic_stop_price        NUMERIC(20,8),
    trail_highest_r           NUMERIC(10,4) DEFAULT 0,
    trail_triggered_at        TIMESTAMPTZ,
    exit_price                NUMERIC(20,8),
    exit_reason               VARCHAR(24),
    realized_pnl_usdt         NUMERIC(20,8),
    realized_r_multiple       NUMERIC(10,4),
    fees_usdt                 NUMERIC(20,8),
    opened_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at                 TIMESTAMPTZ,
    last_sync_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT executed_trades_direction_ck CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT executed_trades_status_ck
        CHECK (status IN ('PENDING_PLACE', 'OPEN', 'CLOSING', 'CLOSED', 'FAILED', 'CANCELLED')),
    CONSTRAINT executed_trades_exit_reason_ck
        CHECK (exit_reason IS NULL OR exit_reason IN
            ('TARGET', 'INITIAL_STOP', 'TRAIL_STOP', 'EXPIRED', 'FLIP_CLOSE', 'MANUAL', 'KILL'))
);

CREATE INDEX IF NOT EXISTS idx_executed_trades_signal          ON executed_trades(signal_id);
CREATE INDEX IF NOT EXISTS idx_executed_trades_status          ON executed_trades(status, opened_at DESC);
CREATE INDEX IF NOT EXISTS idx_executed_trades_account_symbol  ON executed_trades(exchange_account_id, symbol, status);

-- =====================================================================
-- execution_events — append-only audit log. Every decision and state
-- transition. JSONB metadata carries call-site context.
-- =====================================================================

CREATE TABLE IF NOT EXISTS execution_events (
    id                    BIGSERIAL PRIMARY KEY,
    exchange_account_id   BIGINT NOT NULL REFERENCES exchange_accounts(id) ON DELETE RESTRICT,
    event_type            VARCHAR(48) NOT NULL,
    signal_id             VARCHAR(64),
    executed_trade_id     BIGINT REFERENCES executed_trades(id) ON DELETE SET NULL,
    metadata              JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_execution_events_account ON execution_events(exchange_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_execution_events_type    ON execution_events(event_type, created_at DESC);
