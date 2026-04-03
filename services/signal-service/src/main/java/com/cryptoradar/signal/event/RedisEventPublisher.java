package com.cryptoradar.signal.event;

import com.cryptoradar.signal.model.SignalOverview;
import com.cryptoradar.signal.model.TradingSignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

@ApplicationScoped
public class RedisEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedisEventPublisher.class);
    private static final String SIGNALS_CHANNEL = "crypto:signals";

    private final ReactivePubSubCommands<String> pubSub;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public RedisEventPublisher(ReactiveRedisDataSource redisDataSource) {
        this.pubSub = redisDataSource.pubsub(String.class);
    }

    public void publishOverview(SignalOverview overview) {
        publish(Map.of("type", "overview", "data", overview));
    }

    public void publishAlert(TradingSignal signal) {
        publish(Map.of("type", "alert", "data", signal));
    }

    private void publish(Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            pubSub.publish(SIGNALS_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published signal event to %d subscribers", count),
                            error -> LOG.errorf(error, "Failed to publish signal event to Redis")
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize signal event");
        }
    }
}
