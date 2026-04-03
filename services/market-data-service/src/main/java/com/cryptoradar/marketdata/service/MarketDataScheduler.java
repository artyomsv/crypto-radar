package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.client.BinanceClient;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MarketDataScheduler {

    private static final Logger LOG = Logger.getLogger(MarketDataScheduler.class);

    @Inject
    MarketDataService marketDataService;

    @Inject
    BinanceClient binanceClient;

    /**
     * Seed the database with historical 1h candles on startup.
     */
    void onStartup(@Observes StartupEvent event) {
        LOG.info("Seeding historical candle data...");
        for (String symbol : binanceClient.getTrackedSymbols()) {
            try {
                marketDataService.fetchAndStoreCandles(symbol, "1h", 500);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to seed candles for %s", symbol);
            }
        }
        LOG.info("Historical candle seeding complete");
    }

    /**
     * Fetch and store current prices every 15 seconds.
     */
    @Scheduled(every = "{scheduler.price.interval}")
    void fetchPrices() {
        try {
            marketDataService.fetchAndStorePrices();
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled price fetch failed");
        }
    }

    /**
     * Fetch and store latest 1h candles every 60 seconds.
     */
    @Scheduled(every = "{scheduler.candle.interval}")
    void fetchCandles() {
        for (String symbol : binanceClient.getTrackedSymbols()) {
            try {
                marketDataService.fetchAndStoreCandles(symbol, "1h", 5);
            } catch (Exception e) {
                LOG.errorf(e, "Scheduled candle fetch failed for %s", symbol);
            }
        }
    }
}
