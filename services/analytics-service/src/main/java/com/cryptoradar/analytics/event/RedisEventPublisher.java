package com.cryptoradar.analytics.event;

import com.cryptoradar.analytics.model.MarketAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RedisEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedisEventPublisher.class);
    private static final String ANALYTICS_CHANNEL = "crypto:analytics";

    private final ReactivePubSubCommands<String> pubSub;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public RedisEventPublisher(ReactiveRedisDataSource redisDataSource) {
        this.pubSub = redisDataSource.pubsub(String.class);
    }

    public void publishAnalysis(MarketAnalysis analysis) {
        try {
            String json = objectMapper.writeValueAsString(analysis);
            pubSub.publish(ANALYTICS_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published analytics for %s to %d subscribers",
                                    analysis.getSymbol(), count),
                            error -> LOG.errorf(error, "Failed to publish analytics for %s to Redis",
                                    analysis.getSymbol())
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize analytics for %s", analysis.getSymbol());
        }
    }
}
