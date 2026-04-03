package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.client.BinanceClient;
import com.cryptoradar.marketdata.model.Candle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Detects gaps in stored candle data and backfills from Binance.
 * Handles pagination (Binance max 1000 per request) and rate limiting.
 */
@ApplicationScoped
public class BackfillService {

    private static final Logger LOG = Logger.getLogger(BackfillService.class);
    private static final int BINANCE_MAX_LIMIT = 1000;
    // Rate limiting is handled by BinanceRateLimiter

    // How far back to backfill for each interval on first run
    private static final Map<String, Duration> BACKFILL_DEPTH = Map.of(
            "1m", Duration.ofDays(7),
            "5m", Duration.ofDays(30),
            "15m", Duration.ofDays(60),
            "1h", Duration.ofDays(180),
            "4h", Duration.ofDays(365),
            "1d", Duration.ofDays(1000)
    );

    // Candle duration for gap calculation
    private static final Map<String, Duration> INTERVAL_DURATION = Map.of(
            "1m", Duration.ofMinutes(1),
            "5m", Duration.ofMinutes(5),
            "15m", Duration.ofMinutes(15),
            "1h", Duration.ofHours(1),
            "4h", Duration.ofHours(4),
            "1d", Duration.ofDays(1)
    );

    @Inject
    BinanceClient binanceClient;

    @Inject
    EntityManager entityManager;

    @Inject
    MarketDataService marketDataService;

    /**
     * Backfill candles for a symbol and interval.
     * Detects the oldest stored candle and fills backward to the configured depth.
     * Also fills forward gaps (e.g., server was down).
     */
    public BackfillResult backfill(String symbol, String interval) {
        Duration depth = BACKFILL_DEPTH.getOrDefault(interval, Duration.ofDays(30));
        Instant targetStart = Instant.now().minus(depth);

        Instant oldestStored = getOldestCandle(symbol, interval);
        Instant newestStored = getNewestCandle(symbol, interval);

        int totalFetched = 0;

        if (oldestStored == null) {
            // No data at all — full backfill from targetStart to now
            LOG.infof("[Backfill] %s [%s] — no existing data, backfilling %s of history",
                    symbol, interval, depth);
            totalFetched = fetchRange(symbol, interval, targetStart, Instant.now());
        } else {
            // Backward fill: from targetStart to oldestStored
            if (oldestStored.isAfter(targetStart)) {
                LOG.infof("[Backfill] %s [%s] — filling backward from %s to %s",
                        symbol, interval, targetStart, oldestStored);
                totalFetched += fetchRange(symbol, interval, targetStart,
                        oldestStored.minus(1, ChronoUnit.MILLIS));
            }

            // Forward fill: from newestStored to now (gap from server downtime)
            Duration candleDuration = INTERVAL_DURATION.getOrDefault(interval, Duration.ofHours(1));
            Instant expectedNext = newestStored.plus(candleDuration);
            if (expectedNext.isBefore(Instant.now().minus(candleDuration))) {
                LOG.infof("[Backfill] %s [%s] — filling forward gap from %s to now",
                        symbol, interval, newestStored);
                totalFetched += fetchRange(symbol, interval, expectedNext, Instant.now());
            }
        }

        // Update backfill status
        updateBackfillStatus(symbol, interval);

        return new BackfillResult(symbol, interval, totalFetched,
                getOldestCandle(symbol, interval), getNewestCandle(symbol, interval),
                getCandleCount(symbol, interval));
    }

    /**
     * Fetch candles from startTime to endTime, paginating in batches of 1000.
     */
    private int fetchRange(String symbol, String interval, Instant start, Instant end) {
        int totalFetched = 0;
        long startMs = start.toEpochMilli();
        long endMs = end.toEpochMilli();

        while (startMs < endMs) {
            List<Candle> batch = binanceClient.fetchKlines(
                    symbol, interval, BINANCE_MAX_LIMIT, startMs, endMs);

            if (batch.isEmpty()) {
                break;
            }

            marketDataService.upsertCandlesBatch(batch);
            totalFetched += batch.size();

            // Move start forward past the last candle in the batch
            long lastCandleTime = batch.get(batch.size() - 1).getTime().toEpochMilli();
            startMs = lastCandleTime + 1;

            if (batch.size() < BINANCE_MAX_LIMIT) {
                break; // No more data available
            }
        }

        if (totalFetched > 0) {
            LOG.infof("[Backfill] %s [%s] — stored %d candles", symbol, interval, totalFetched);
        }

        return totalFetched;
    }

    private Instant getOldestCandle(String symbol, String interval) {
        try {
            Object result = entityManager.createNativeQuery(
                            "SELECT MIN(time) FROM candles WHERE symbol = :symbol AND interval = :interval")
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .getSingleResult();
            if (result instanceof java.sql.Timestamp ts) {
                return ts.toInstant();
            }
        } catch (Exception e) {
            LOG.debugf("No oldest candle for %s [%s]", symbol, interval);
        }
        return null;
    }

    private Instant getNewestCandle(String symbol, String interval) {
        try {
            Object result = entityManager.createNativeQuery(
                            "SELECT MAX(time) FROM candles WHERE symbol = :symbol AND interval = :interval")
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .getSingleResult();
            if (result instanceof java.sql.Timestamp ts) {
                return ts.toInstant();
            }
        } catch (Exception e) {
            LOG.debugf("No newest candle for %s [%s]", symbol, interval);
        }
        return null;
    }

    private long getCandleCount(String symbol, String interval) {
        try {
            Object result = entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM candles WHERE symbol = :symbol AND interval = :interval")
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .getSingleResult();
            if (result instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception e) {
            LOG.debugf("Cannot count candles for %s [%s]", symbol, interval);
        }
        return 0;
    }

    @Transactional
    void updateBackfillStatus(String symbol, String interval) {
        try {
            entityManager.createNativeQuery("""
                INSERT INTO backfill_status (symbol, interval, oldest_candle, newest_candle, candle_count, last_backfill_at, is_complete)
                VALUES (:symbol, :interval,
                    (SELECT MIN(time) FROM candles WHERE symbol = :symbol AND interval = :interval),
                    (SELECT MAX(time) FROM candles WHERE symbol = :symbol AND interval = :interval),
                    (SELECT COUNT(*) FROM candles WHERE symbol = :symbol AND interval = :interval),
                    NOW(), true)
                ON CONFLICT (symbol, interval) DO UPDATE SET
                    oldest_candle = EXCLUDED.oldest_candle,
                    newest_candle = EXCLUDED.newest_candle,
                    candle_count = EXCLUDED.candle_count,
                    last_backfill_at = NOW(),
                    is_complete = true
                """)
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .executeUpdate();
        } catch (Exception e) {
            LOG.warnf("Failed to update backfill status for %s [%s]: %s", symbol, interval, e.getMessage());
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record BackfillResult(String symbol, String interval, int fetchedCount,
                                  Instant oldestCandle, Instant newestCandle, long totalStored) {}
}
