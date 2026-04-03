package com.cryptoradar.analytics.service;

import com.cryptoradar.analytics.event.RedisEventPublisher;
import com.cryptoradar.analytics.model.MarketAnalysis;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AnalyticsScheduler {

    private static final Logger LOG = Logger.getLogger(AnalyticsScheduler.class);

    private final AnalyticsService analyticsService;
    private final RedisEventPublisher redisPublisher;

    public AnalyticsScheduler(AnalyticsService analyticsService, RedisEventPublisher redisPublisher) {
        this.analyticsService = analyticsService;
        this.redisPublisher = redisPublisher;
    }

    void onStart(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            LOG.info("Computing initial market analysis (30s after startup)...");
            runFullAnalysis();
        }, 30, TimeUnit.SECONDS);
    }

    @Scheduled(every = "${scheduler.analysis.interval}", identity = "analytics-periodic")
    void recomputeAnalysis() {
        LOG.info("Scheduled analysis recomputation starting");
        runFullAnalysis();
    }

    private void runFullAnalysis() {
        for (String symbol : analyticsService.getTopSymbols()) {
            try {
                MarketAnalysis analysis = analyticsService.computeAnalysis(symbol);
                redisPublisher.publishAnalysis(analysis);
                LOG.debugf("Published analysis for %s (score=%.1f, trend=%s)",
                        symbol, analysis.getOverallScore(), analysis.getTrendDirection());
            } catch (Exception e) {
                LOG.errorf(e, "Failed to compute/publish analysis for %s", symbol);
            }
        }
        LOG.info("Scheduled analysis recomputation complete");
    }
}
