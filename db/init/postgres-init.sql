-- Sequence for Panache entity ID generation
CREATE SEQUENCE IF NOT EXISTS news_articles_seq START WITH 1 INCREMENT BY 50;

-- News articles table
CREATE TABLE IF NOT EXISTS news_articles (
    id INTEGER PRIMARY KEY DEFAULT nextval('news_articles_seq'),
    external_id VARCHAR(255) UNIQUE,
    title TEXT NOT NULL,
    body TEXT,
    url TEXT,
    source VARCHAR(100),
    image_url TEXT,
    published_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ DEFAULT NOW(),
    sentiment_score DOUBLE PRECISION,
    sentiment_label VARCHAR(20),
    categories TEXT,
    related_symbols TEXT,
    language VARCHAR(10) DEFAULT 'en'
);

CREATE INDEX IF NOT EXISTS idx_news_published ON news_articles (published_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_symbols ON news_articles (related_symbols);
CREATE INDEX IF NOT EXISTS idx_news_sentiment ON news_articles (sentiment_label, published_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_source ON news_articles (source, published_at DESC);

-- Aggregated sentiment per symbol per day
CREATE TABLE IF NOT EXISTS daily_sentiment (
    date DATE NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    avg_sentiment DOUBLE PRECISION,
    positive_count INTEGER DEFAULT 0,
    negative_count INTEGER DEFAULT 0,
    neutral_count INTEGER DEFAULT 0,
    total_articles INTEGER DEFAULT 0,
    PRIMARY KEY (date, symbol)
);

-- News sources configuration
CREATE TABLE IF NOT EXISTS news_sources (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    api_url TEXT,
    is_active BOOLEAN DEFAULT true,
    last_fetched_at TIMESTAMPTZ,
    fetch_interval_minutes INTEGER DEFAULT 5
);

INSERT INTO news_sources (name, api_url, is_active) VALUES
    ('CryptoCompare', 'https://min-api.cryptocompare.com/data/v2/news/', true),
    ('CoinGecko News', 'https://api.coingecko.com/api/v3', true)
ON CONFLICT DO NOTHING;
