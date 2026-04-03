package com.cryptoradar.whale.scheduler;

import com.cryptoradar.whale.event.RedisEventPublisher;
import com.cryptoradar.whale.model.WhaleMarketOverview;
import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.alert.WhaleAlertProvider;
import com.cryptoradar.whale.service.WhaleAnalyticsService;
import com.cryptoradar.whale.service.WhaleFlowService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class WhaleScheduler {

    private static final Logger LOG = Logger.getLogger(WhaleScheduler.class);

    private final WhaleAnalyticsService analyticsService;
    private final RedisEventPublisher redisPublisher;
    private final WhaleAlertProvider whaleAlertProvider;
    private final WhaleFlowService whaleFlowService;

    public WhaleScheduler(WhaleAnalyticsService analyticsService,
                          RedisEventPublisher redisPublisher,
                          WhaleAlertProvider whaleAlertProvider,
                          WhaleFlowService whaleFlowService) {
        this.analyticsService = analyticsService;
        this.redisPublisher = redisPublisher;
        this.whaleAlertProvider = whaleAlertProvider;
        this.whaleFlowService = whaleFlowService;
    }

    @Scheduled(every = "${scheduler.flow.interval}", identity = "whale-flow-recompute")
    void recomputeFlowSummaries() {
        LOG.debug("Recomputing whale flow summaries");
        for (String symbol : analyticsService.getTopSymbols()) {
            try {
                analyticsService.computeAnalytics(symbol);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to recompute flow summary for %s", symbol);
            }
        }
    }

    @Scheduled(every = "${scheduler.analytics.interval}", identity = "whale-analytics-full")
    void recomputeFullAnalytics() {
        LOG.info("Scheduled whale analytics recomputation starting");
        try {
            WhaleMarketOverview overview = analyticsService.computeMarketOverview();
            redisPublisher.publishMarketOverview(overview);
            LOG.infof("Published whale market overview: %d active symbols, pressure=%.1f (%s)",
                    overview.getActiveSymbolCount(),
                    overview.getOverallPressure(),
                    overview.getOverallPressureLabel());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to compute/publish whale market overview");
        }
    }

    /**
     * Fetch whale transactions from Whale Alert every 30 seconds.
     * Free tier: 10 req/min, $500K min, so 2 req/min is efficient.
     * Uses 5-minute lookback window with dedup to catch all transactions.
     */
    @Scheduled(every = "30s", identity = "whale-alert-fetch")
    void fetchWhaleAlertTransactions() {
        if (!whaleAlertProvider.isEnabled()) return;

        try {
            List<WhaleTransaction> transactions = whaleAlertProvider.fetchRecentTransactions();
            int stored = 0;
            for (WhaleTransaction tx : transactions) {
                if (whaleFlowService.processWhaleTransactionIfNew(tx)) {
                    stored++;
                }
            }
            if (stored > 0) {
                LOG.infof("Whale Alert: fetched %d, stored %d new transactions", transactions.size(), stored);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Whale Alert fetch failed");
        }
    }
}
