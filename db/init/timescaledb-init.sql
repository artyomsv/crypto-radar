-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Crypto assets reference table
CREATE TABLE IF NOT EXISTS crypto_assets (
    symbol VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    rank INTEGER,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- OHLCV Candle data - will be converted to hypertable
CREATE TABLE IF NOT EXISTS candles (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    open DOUBLE PRECISION NOT NULL,
    high DOUBLE PRECISION NOT NULL,
    low DOUBLE PRECISION NOT NULL,
    close DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    quote_volume DOUBLE PRECISION DEFAULT 0,
    trade_count INTEGER DEFAULT 0,
    interval VARCHAR(10) NOT NULL DEFAULT '1h',
    UNIQUE(time, symbol, interval)
);

-- Convert to hypertable partitioned by time
SELECT create_hypertable('candles', 'time', if_not_exists => TRUE);

-- Indexes for efficient multi-timeframe queries
CREATE INDEX IF NOT EXISTS idx_candles_symbol_interval_time ON candles (symbol, interval, time DESC);
CREATE INDEX IF NOT EXISTS idx_candles_interval ON candles (interval, symbol, time DESC);

-- Real-time price snapshots
CREATE TABLE IF NOT EXISTS price_snapshots (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    price_change_24h DOUBLE PRECISION,
    price_change_pct_24h DOUBLE PRECISION,
    volume_24h DOUBLE PRECISION,
    market_cap DOUBLE PRECISION,
    UNIQUE(time, symbol)
);

SELECT create_hypertable('price_snapshots', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_price_symbol_time ON price_snapshots (symbol, time DESC);

-- Backfill tracking: records what intervals have been backfilled per symbol
CREATE TABLE IF NOT EXISTS backfill_status (
    symbol VARCHAR(20) NOT NULL,
    interval VARCHAR(10) NOT NULL,
    oldest_candle TIMESTAMPTZ,
    newest_candle TIMESTAMPTZ,
    candle_count BIGINT DEFAULT 0,
    last_backfill_at TIMESTAMPTZ DEFAULT NOW(),
    is_complete BOOLEAN DEFAULT false,
    PRIMARY KEY (symbol, interval)
);

-- Backfill depth configuration (days per interval, editable via UI)
CREATE TABLE IF NOT EXISTS backfill_config (
    interval VARCHAR(10) PRIMARY KEY,
    depth_days INTEGER NOT NULL CHECK (depth_days >= 1 AND depth_days <= 5000),
    description VARCHAR(100)
);

INSERT INTO backfill_config (interval, depth_days, description) VALUES
    ('1m',  30,   '1 minute candles'),
    ('5m',  180,  '5 minute candles'),
    ('15m', 365,  '15 minute candles'),
    ('30m', 730,  '30 minute candles'),
    ('1h',  1000, '1 hour candles'),
    ('2h',  1500, '2 hour candles'),
    ('4h',  1500, '4 hour candles'),
    ('8h',  1500, '8 hour candles'),
    ('12h', 1500, '12 hour candles'),
    ('1d',  2500, '1 day candles'),
    ('1w',  3000, '1 week candles')
ON CONFLICT (interval) DO NOTHING;

-- Insert top 14 crypto assets
INSERT INTO crypto_assets (symbol, name, rank) VALUES
    ('BTCUSDT', 'Bitcoin', 1),
    ('ETHUSDT', 'Ethereum', 2),
    ('XRPUSDT', 'XRP', 3),
    ('BNBUSDT', 'BNB', 4),
    ('SOLUSDT', 'Solana', 5),
    ('TRXUSDT', 'TRON', 6),
    ('DOGEUSDT', 'Dogecoin', 7),
    ('BCHUSDT', 'Bitcoin Cash', 8),
    ('ADAUSDT', 'Cardano', 9),
    ('LINKUSDT', 'Chainlink', 10),
    ('XMRUSDT', 'Monero', 11),
    ('XLMUSDT', 'Stellar', 12),
    ('LTCUSDT', 'Litecoin', 13),
    ('ZECUSDT', 'Zcash', 14)
ON CONFLICT (symbol) DO NOTHING;

-- Continuous aggregates for faster dashboard queries (derived from 1h candles)
CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1d_agg
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', time) AS bucket,
    symbol,
    first(open, time) AS open,
    max(high) AS high,
    min(low) AS low,
    last(close, time) AS close,
    sum(volume) AS volume,
    sum(trade_count) AS trade_count
FROM candles
WHERE interval = '1h'
GROUP BY bucket, symbol
WITH NO DATA;

SELECT add_continuous_aggregate_policy('candles_1d_agg',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);

-- NO retention policies: keep all historical data indefinitely

-- Enable compression on hypertables for disk space efficiency
ALTER TABLE candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol,interval',
    timescaledb.compress_orderby = 'time DESC'
);

ALTER TABLE price_snapshots SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol',
    timescaledb.compress_orderby = 'time DESC'
);

-- Auto-compress data older than 7 days
SELECT add_compression_policy('candles', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('price_snapshots', INTERVAL '7 days', if_not_exists => TRUE);

-- Price alerts
CREATE TABLE IF NOT EXISTS price_alerts (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    condition VARCHAR(10) NOT NULL,  -- 'ABOVE' or 'BELOW'
    target_price DOUBLE PRECISION NOT NULL,
    is_active BOOLEAN DEFAULT true,
    is_triggered BOOLEAN DEFAULT false,
    triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    note VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_alerts_active ON price_alerts (is_active, symbol);

-- Portfolio positions
CREATE TABLE IF NOT EXISTS portfolio_positions (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    entry_price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    side VARCHAR(10) NOT NULL DEFAULT 'LONG',
    note VARCHAR(255),
    opened_at TIMESTAMPTZ DEFAULT NOW(),
    is_open BOOLEAN DEFAULT true,
    closed_at TIMESTAMPTZ,
    close_price DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_portfolio_open ON portfolio_positions (is_open, symbol);
