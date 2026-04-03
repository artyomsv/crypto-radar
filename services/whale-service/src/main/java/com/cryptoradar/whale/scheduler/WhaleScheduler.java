package com.cryptoradar.whale.scheduler;

import com.cryptoradar.whale.event.RedisEventPublisher;
import com.cryptoradar.whale.model.WhaleMarketOverview;
import com.cryptoradar.whale.service.WhaleAnalyticsService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WhaleScheduler {

    private static final Logger LOG = Logger.getLogger(WhaleScheduler.class);

    private final WhaleAnalyticsService analyticsService;
    private final RedisEventPublisher redisPublisher;

    public WhaleScheduler(WhaleAnalyticsService analyticsService, RedisEventPublisher redisPublisher) {
        this.analyticsService = analyticsService;
        this.redisPublisher = redisPublisher;
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
}
