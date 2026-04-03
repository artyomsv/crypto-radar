package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.client.BinanceClient;
import com.cryptoradar.marketdata.event.RedisEventPublisher;
import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.CryptoAsset;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

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
    RedisEventPublisher redisEventPublisher;

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
        try {
            return entityManager.createNativeQuery("""
                SELECT DISTINCT ON (symbol) time, symbol, price, price_change_24h, price_change_pct_24h, volume_24h, market_cap
                FROM price_snapshots
                ORDER BY symbol, time DESC
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
        for (Candle candle : candles) {
            entityManager.createNativeQuery("""
                INSERT INTO candles (time, symbol, interval, open, high, low, close, volume, quote_volume, trade_count)
                VALUES (:time, :symbol, :interval, :open, :high, :low, :close, :volume, :quoteVolume, :tradeCount)
                ON CONFLICT (time, symbol, interval) DO UPDATE SET
                    open = EXCLUDED.open,
                    high = EXCLUDED.high,
                    low = EXCLUDED.low,
                    close = EXCLUDED.close,
                    volume = EXCLUDED.volume,
                    quote_volume = EXCLUDED.quote_volume,
                    trade_count = EXCLUDED.trade_count
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
