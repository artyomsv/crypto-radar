package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.client.BinanceClient;
import com.cryptoradar.marketdata.event.RedisEventPublisher;
import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.CryptoAsset;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class MarketDataService {

    private static final Logger LOG = Logger.getLogger(MarketDataService.class);

    @Inject
    BinanceClient binanceClient;

    @Inject
    EntityManager entityManager;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    RedisEventPublisher redisEventPublisher;

    @Inject
    AlertService alertService;

    @Transactional
    public void fetchAndStoreCandles(String symbol, String interval, int limit) {
        List<Candle> candles = binanceClient.fetchKlines(symbol, interval, limit);
        if (candles.isEmpty()) {
            LOG.warnf("No candles fetched for %s [%s]", symbol, interval);
            return;
        }

        upsertCandles(candles);
        redisEventPublisher.publishCandleUpdate(symbol, candles);
        LOG.infof("Stored %d candles for %s [%s]", candles.size(), symbol, interval);
    }

    @Transactional
    public void fetchAndStorePrices() {
        List<PriceSnapshot> snapshots = binanceClient.fetchAll24hrTickers();
        if (snapshots.isEmpty()) {
            LOG.warn("No price snapshots fetched");
            return;
        }

        for (PriceSnapshot snapshot : snapshots) {
            entityManager.createNativeQuery("""
                INSERT INTO price_snapshots (time, symbol, price, price_change_24h, price_change_pct_24h, volume_24h, market_cap)
                VALUES (:time, :symbol, :price, :priceChange24h, :priceChangePct24h, :volume24h, :marketCap)
                ON CONFLICT (time, symbol) DO UPDATE SET
                    price = EXCLUDED.price,
                    price_change_24h = EXCLUDED.price_change_24h,
                    price_change_pct_24h = EXCLUDED.price_change_pct_24h,
                    volume_24h = EXCLUDED.volume_24h,
                    market_cap = EXCLUDED.market_cap
                """)
                    .setParameter("time", snapshot.getTime())
                    .setParameter("symbol", snapshot.getSymbol())
                    .setParameter("price", snapshot.getPrice())
                    .setParameter("priceChange24h", snapshot.getPriceChange24h())
                    .setParameter("priceChangePct24h", snapshot.getPriceChangePct24h())
                    .setParameter("volume24h", snapshot.getVolume24h())
                    .setParameter("marketCap", snapshot.getMarketCap())
                    .executeUpdate();
        }

        redisEventPublisher.publishPriceUpdate(snapshots);
        LOG.infof("Stored %d price snapshots", snapshots.size());

        // Check price alerts for each symbol
        for (PriceSnapshot snapshot : snapshots) {
            alertService.checkAlerts(snapshot.getSymbol(), snapshot.getPrice());
        }
    }

    /**
     * Lightweight price fetch — only publishes to Redis, no DB storage.
     * Used for 1-second real-time price streaming.
     */
    public void fetchAndPublishLightweightPrices() {
        java.util.Map<String, Double> prices = binanceClient.fetchAllPricesLightweight();
        if (prices.isEmpty()) return;

        // Build lightweight PriceSnapshot list (only price field populated)
        List<PriceSnapshot> snapshots = new java.util.ArrayList<>();
        java.time.Instant now = java.time.Instant.now();
        for (var entry : prices.entrySet()) {
            snapshots.add(new PriceSnapshot(now, entry.getKey(), entry.getValue(),
                    null, null, null, null));
        }

        redisEventPublisher.publishPriceUpdate(snapshots);
    }

    @SuppressWarnings("unchecked")
    public List<Candle> getCandles(String symbol, String interval, int limit) {
        try {
            return entityManager.createNativeQuery("""
                SELECT time, symbol, interval, open, high, low, close, volume, quote_volume, trade_count
                FROM candles
                WHERE symbol = :symbol AND interval = :interval
                ORDER BY time DESC
                LIMIT :limit
                """, Candle.class)
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .setParameter("limit", limit)
                    .getResultList();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query candles for %s [%s]", symbol, interval);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public List<PriceSnapshot> getLatestPrices() {
        // DISTINCT ON across the full price_snapshots hypertable forced full
        // decompression of every chunk (compressed columnar batches cannot be
        // index-scanned by TimescaleDB even when an index exists). A lateral
        // join from crypto_assets turns this into ~14 per-symbol point lookups
        // using the (symbol, time DESC) index: measured 0.6ms vs 1500ms.
        try {
            return entityManager.createNativeQuery("""
                SELECT latest.time, latest.symbol, latest.price,
                       latest.price_change_24h, latest.price_change_pct_24h,
                       latest.volume_24h, latest.market_cap
                FROM crypto_assets a
                CROSS JOIN LATERAL (
                    SELECT * FROM price_snapshots
                    WHERE symbol = a.symbol
                    ORDER BY time DESC
                    LIMIT 1
                ) latest
                WHERE a.is_active = true
                """, PriceSnapshot.class)
                    .getResultList();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query latest prices");
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public List<CryptoAsset> getAssets() {
        try {
            return entityManager.createNativeQuery("""
                SELECT symbol, name, rank, is_active
                FROM crypto_assets
                WHERE is_active = true
                ORDER BY rank
                """, CryptoAsset.class)
                    .getResultList();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query crypto assets");
            return Collections.emptyList();
        }
    }

    /**
     * Public upsert for backfill service — batch insert with ON CONFLICT.
     */
    @Transactional
    public void upsertCandlesBatch(List<Candle> candles) {
        upsertCandles(candles);
    }

    private void upsertCandles(List<Candle> candles) {
        if (candles.isEmpty()) return;
        String sql = """
            INSERT INTO candles (time, symbol, interval, open, high, low, close, volume, quote_volume, trade_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, interval) DO UPDATE SET
                open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
                close = EXCLUDED.close, volume = EXCLUDED.volume,
                quote_volume = EXCLUDED.quote_volume, trade_count = EXCLUDED.trade_count
            """;
        // Hibernate's EntityManager cannot unwrap to java.sql.Connection in
        // Quarkus 3 / Hibernate 6 — only the proprietary Session interface is
        // unwrappable. AgroalDataSource is the supported path for raw JDBC
        // batch operations. Previously this fell through to per-row inserts
        // on every batch (~1000 WARN/30min in prod logs).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Candle candle : candles) {
                stmt.setObject(1, java.sql.Timestamp.from(candle.getTime()));
                stmt.setString(2, candle.getSymbol());
                stmt.setString(3, candle.getInterval());
                stmt.setDouble(4, candle.getOpen());
                stmt.setDouble(5, candle.getHigh());
                stmt.setDouble(6, candle.getLow());
                stmt.setDouble(7, candle.getClose());
                stmt.setDouble(8, candle.getVolume());
                stmt.setDouble(9, candle.getQuoteVolume());
                stmt.setInt(10, candle.getTradeCount());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (Exception e) {
            LOG.warnf("Batch upsert failed, falling back to individual inserts: %s", e.getMessage());
            for (Candle candle : candles) {
                entityManager.createNativeQuery("""
                    INSERT INTO candles (time, symbol, interval, open, high, low, close, volume, quote_volume, trade_count)
                    VALUES (:time, :symbol, :interval, :open, :high, :low, :close, :volume, :quoteVolume, :tradeCount)
                    ON CONFLICT (time, symbol, interval) DO UPDATE SET
                        open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
                        close = EXCLUDED.close, volume = EXCLUDED.volume,
                        quote_volume = EXCLUDED.quote_volume, trade_count = EXCLUDED.trade_count
                    """)
                        .setParameter("time", candle.getTime())
                        .setParameter("symbol", candle.getSymbol())
                        .setParameter("interval", candle.getInterval())
                        .setParameter("open", candle.getOpen())
                        .setParameter("high", candle.getHigh())
                        .setParameter("low", candle.getLow())
                        .setParameter("close", candle.getClose())
                        .setParameter("volume", candle.getVolume())
                        .setParameter("quoteVolume", candle.getQuoteVolume())
                        .setParameter("tradeCount", candle.getTradeCount())
                        .executeUpdate();
            }
        }
    }
}
