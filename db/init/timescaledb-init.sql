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

-- Create indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_candles_symbol_time ON candles (symbol, time DESC);
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

-- Insert top 10 crypto assets
INSERT INTO crypto_assets (symbol, name, rank) VALUES
    ('BTCUSDT', 'Bitcoin', 1),
    ('ETHUSDT', 'Ethereum', 2),
    ('BNBUSDT', 'BNB', 3),
    ('SOLUSDT', 'Solana', 4),
    ('XRPUSDT', 'XRP', 5),
    ('ADAUSDT', 'Cardano', 6),
    ('AVAXUSDT', 'Avalanche', 7),
    ('DOTUSDT', 'Polkadot', 8),
    ('LINKUSDT', 'Chainlink', 9),
    ('DOGEUSDT', 'Dogecoin', 10)
ON CONFLICT (symbol) DO NOTHING;

-- Continuous aggregates for faster dashboard queries
CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1d
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

-- Refresh policy for continuous aggregate
SELECT add_continuous_aggregate_policy('candles_1d',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);

-- Retention policy: keep detailed candles for 90 days
SELECT add_retention_policy('candles', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('price_snapshots', INTERVAL '30 days', if_not_exists => TRUE);
