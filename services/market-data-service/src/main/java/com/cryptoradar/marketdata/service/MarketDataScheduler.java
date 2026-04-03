package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.client.BinanceClient;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MarketDataScheduler {

    private static final Logger LOG = Logger.getLogger(MarketDataScheduler.class);

    // All intervals we collect (Binance supported intervals)
    private static final List<String> ALL_INTERVALS = List.of(
            "1m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "1w"
    );

    @Inject
    MarketDataService marketDataService;

    @Inject
    BinanceClient binanceClient;

    @Inject
    BackfillService backfillService;

    /**
     * On startup: run backfill in a background thread to avoid blocking startup.
     * Fills gaps and fetches historical data for all symbols and intervals.
     */
    void onStartup(@Observes StartupEvent event) {
        LOG.info("Starting background backfill for all symbols and intervals...");
        Executors.newSingleThreadScheduledExecutor().schedule(this::runBackfill, 5, TimeUnit.SECONDS);
    }

    private void runBackfill() {
        Set<String> symbols = binanceClient.getTrackedSymbols();
        LOG.infof("=== Backfill starting for %d symbols x %d intervals ===",
                symbols.size(), ALL_INTERVALS.size());

        // Prioritize: 1h and 1d first (most important for analysis), then others
        List<String> priorityOrder = List.of("1h", "1d", "4h", "15m", "5m", "1m");

        for (String interval : priorityOrder) {
            for (String symbol : symbols) {
                try {
                    BackfillService.BackfillResult result = backfillService.backfill(symbol, interval);
                    if (result.fetchedCount() > 0) {
                        LOG.infof("[Backfill] %s [%s] — fetched %d, total stored: %d (oldest: %s)",
                                symbol, interval, result.fetchedCount(), result.totalStored(),
                                result.oldestCandle());
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "[Backfill] Failed for %s [%s]", symbol, interval);
                }
            }
            LOG.infof("=== Backfill complete for interval %s ===", interval);
        }

        LOG.info("=== Full backfill complete ===");
    }

    // --- Price schedulers: dual-fetch strategy ---

    /** Lightweight price fetch every 3 seconds — only publishes to Redis/WebSocket (weight 4) */
    @Scheduled(every = "3s", identity = "prices-realtime")
    void fetchRealtimePrices() {
        try {
            marketDataService.fetchAndPublishLightweightPrices();
        } catch (Exception e) {
            LOG.errorf(e, "Realtime price fetch failed");
        }
    }

    /** Full 24hr ticker fetch every 30 seconds — stores to DB with volume, change %, etc. (weight 40) */
    @Scheduled(every = "30s", identity = "prices-full")
    void fetchFullPrices() {
        try {
            marketDataService.fetchAndStorePrices();
        } catch (Exception e) {
            LOG.errorf(e, "Full price fetch failed");
        }
    }

    /** 1m candles: fetch every 60 seconds */
    @Scheduled(every = "60s", identity = "candles-1m")
    void fetch1mCandles() {
        fetchLatestCandles("1m", 3);
    }

    /** 5m candles: fetch every 5 minutes */
    @Scheduled(every = "5m", identity = "candles-5m")
    void fetch5mCandles() {
        fetchLatestCandles("5m", 3);
    }

    /** 15m candles: fetch every 5 minutes */
    @Scheduled(every = "5m", identity = "candles-15m")
    void fetch15mCandles() {
        fetchLatestCandles("15m", 3);
    }

    /** 1h candles: fetch every 60 seconds */
    @Scheduled(every = "{scheduler.candle.interval}", identity = "candles-1h")
    void fetch1hCandles() {
        fetchLatestCandles("1h", 3);
    }

    /** 4h candles: fetch every 15 minutes */
    @Scheduled(every = "15m", identity = "candles-4h")
    void fetch4hCandles() {
        fetchLatestCandles("4h", 3);
    }

    /** 30m candles: fetch every 10 minutes */
    @Scheduled(every = "10m", identity = "candles-30m")
    void fetch30mCandles() {
        fetchLatestCandles("30m", 3);
    }

    /** 2h candles: fetch every 30 minutes */
    @Scheduled(every = "30m", identity = "candles-2h")
    void fetch2hCandles() {
        fetchLatestCandles("2h", 3);
    }

    /** 8h candles: fetch every hour */
    @Scheduled(every = "1h", identity = "candles-8h")
    void fetch8hCandles() {
        fetchLatestCandles("8h", 3);
    }

    /** 12h candles: fetch every hour */
    @Scheduled(every = "1h", identity = "candles-12h")
    void fetch12hCandles() {
        fetchLatestCandles("12h", 3);
    }

    /** 1d candles: fetch every hour */
    @Scheduled(every = "1h", identity = "candles-1d")
    void fetch1dCandles() {
        fetchLatestCandles("1d", 3);
    }

    /** 1w candles: fetch every 4 hours */
    @Scheduled(every = "4h", identity = "candles-1w")
    void fetch1wCandles() {
        fetchLatestCandles("1w", 3);
    }

    private void fetchLatestCandles(String interval, int limit) {
        for (String symbol : binanceClient.getTrackedSymbols()) {
            try {
                marketDataService.fetchAndStoreCandles(symbol, interval, limit);
            } catch (Exception e) {
                LOG.errorf(e, "Scheduled candle fetch failed for %s [%s]", symbol, interval);
            }
        }
    }
}
