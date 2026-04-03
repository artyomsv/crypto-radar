-- Whale Tracker Tables (runs on TimescaleDB instance)

-- Individual whale transactions detected from trade streams
CREATE TABLE IF NOT EXISTS whale_transactions (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    value_usd DOUBLE PRECISION NOT NULL,
    side VARCHAR(4) NOT NULL,  -- 'BUY' or 'SELL'
    source VARCHAR(50) NOT NULL DEFAULT 'binance',
    trade_id VARCHAR(100),
    from_address VARCHAR(255),
    to_address VARCHAR(255),
    from_label VARCHAR(100),
    to_label VARCHAR(100),
    blockchain VARCHAR(50),
    tx_hash VARCHAR(255)
);

SELECT create_hypertable('whale_transactions', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_whale_tx_symbol_time ON whale_transactions (symbol, time DESC);
CREATE INDEX IF NOT EXISTS idx_whale_tx_source ON whale_transactions (source, time DESC);
CREATE INDEX IF NOT EXISTS idx_whale_tx_value ON whale_transactions (value_usd DESC, time DESC);
CREATE INDEX IF NOT EXISTS idx_whale_tx_side ON whale_transactions (side, symbol, time DESC);

-- Aggregated whale flow per symbol per time bucket
CREATE TABLE IF NOT EXISTS whale_flow_summary (
    bucket TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    buy_count INTEGER DEFAULT 0,
    sell_count INTEGER DEFAULT 0,
    buy_volume_usd DOUBLE PRECISION DEFAULT 0,
    sell_volume_usd DOUBLE PRECISION DEFAULT 0,
    net_flow_usd DOUBLE PRECISION DEFAULT 0,
    avg_trade_size_usd DOUBLE PRECISION DEFAULT 0,
    largest_trade_usd DOUBLE PRECISION DEFAULT 0,
    whale_pressure DOUBLE PRECISION DEFAULT 0,  -- -100 (sell) to +100 (buy)
    PRIMARY KEY (bucket, symbol)
);

-- Continuous aggregate for 5-minute whale flow
CREATE MATERIALIZED VIEW IF NOT EXISTS whale_flow_5m
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('5 minutes', time) AS bucket,
    symbol,
    COUNT(*) FILTER (WHERE side = 'BUY') AS buy_count,
    COUNT(*) FILTER (WHERE side = 'SELL') AS sell_count,
    COALESCE(SUM(value_usd) FILTER (WHERE side = 'BUY'), 0) AS buy_volume_usd,
    COALESCE(SUM(value_usd) FILTER (WHERE side = 'SELL'), 0) AS sell_volume_usd,
    COALESCE(SUM(value_usd) FILTER (WHERE side = 'BUY'), 0) -
        COALESCE(SUM(value_usd) FILTER (WHERE side = 'SELL'), 0) AS net_flow_usd,
    AVG(value_usd) AS avg_trade_size_usd,
    MAX(value_usd) AS largest_trade_usd
FROM whale_transactions
GROUP BY bucket, symbol
WITH NO DATA;

SELECT add_continuous_aggregate_policy('whale_flow_5m',
    start_offset => INTERVAL '1 hour',
    end_offset => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes',
    if_not_exists => TRUE);

-- Enable compression for old whale data
ALTER TABLE whale_transactions SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol,source',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('whale_transactions', INTERVAL '7 days', if_not_exists => TRUE);
