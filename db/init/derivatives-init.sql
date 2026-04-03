-- Derivatives data tables (runs on TimescaleDB)

-- Funding rate snapshots
CREATE TABLE IF NOT EXISTS funding_rates (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    funding_rate DOUBLE PRECISION NOT NULL,
    mark_price DOUBLE PRECISION,
    index_price DOUBLE PRECISION,
    UNIQUE(time, symbol)
);
SELECT create_hypertable('funding_rates', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_funding_symbol_time ON funding_rates (symbol, time DESC);

-- Open interest snapshots
CREATE TABLE IF NOT EXISTS open_interest (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    open_interest DOUBLE PRECISION NOT NULL,
    open_interest_usd DOUBLE PRECISION,
    UNIQUE(time, symbol)
);
SELECT create_hypertable('open_interest', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_oi_symbol_time ON open_interest (symbol, time DESC);

-- Liquidation events
CREATE TABLE IF NOT EXISTS liquidations (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    value_usd DOUBLE PRECISION NOT NULL
);
SELECT create_hypertable('liquidations', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_liq_symbol_time ON liquidations (symbol, time DESC);
CREATE INDEX IF NOT EXISTS idx_liq_side ON liquidations (side, time DESC);

-- Long/short ratio snapshots
CREATE TABLE IF NOT EXISTS long_short_ratio (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    long_account DOUBLE PRECISION NOT NULL,
    short_account DOUBLE PRECISION NOT NULL,
    long_short_ratio DOUBLE PRECISION NOT NULL,
    UNIQUE(time, symbol)
);
SELECT create_hypertable('long_short_ratio', 'time', if_not_exists => TRUE);

-- Compression for old data
ALTER TABLE funding_rates SET (timescaledb.compress, timescaledb.compress_segmentby = 'symbol', timescaledb.compress_orderby = 'time DESC');
ALTER TABLE open_interest SET (timescaledb.compress, timescaledb.compress_segmentby = 'symbol', timescaledb.compress_orderby = 'time DESC');
ALTER TABLE liquidations SET (timescaledb.compress, timescaledb.compress_segmentby = 'symbol', timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('funding_rates', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('open_interest', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('liquidations', INTERVAL '7 days', if_not_exists => TRUE);
