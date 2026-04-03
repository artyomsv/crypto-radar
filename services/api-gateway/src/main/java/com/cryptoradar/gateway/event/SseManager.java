package com.cryptoradar.gateway.event;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SseManager {

    private static final Logger LOG = Logger.getLogger(SseManager.class);

    private final BroadcastProcessor<String> pricesBroadcaster = BroadcastProcessor.create();
    private final BroadcastProcessor<String> newsBroadcaster = BroadcastProcessor.create();
    private final BroadcastProcessor<String> analyticsBroadcaster = BroadcastProcessor.create();

    public void broadcastPrices(String json) {
        LOG.debugf("Broadcasting price update: %d chars", json.length());
        pricesBroadcaster.onNext(json);
    }

    public void broadcastNews(String json) {
        LOG.debugf("Broadcasting news update: %d chars", json.length());
        newsBroadcaster.onNext(json);
    }

    public void broadcastAnalytics(String json) {
        LOG.debugf("Broadcasting analytics update: %d chars", json.length());
        analyticsBroadcaster.onNext(json);
    }

    public Multi<String> getPricesStream() {
        return pricesBroadcaster;
    }

    public Multi<String> getNewsStream() {
        return newsBroadcaster;
    }

    public Multi<String> getAnalyticsStream() {
        return analyticsBroadcaster;
    }

    public Multi<String> getAllStreams() {
        return Multi.createBy().merging().streams(
                pricesBroadcaster,
                newsBroadcaster,
                analyticsBroadcaster
        );
    }
}
