package com.cryptoradar.derivatives.event;

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

    private static final String DERIVATIVES_CHANNEL = "crypto:derivatives";

    private final ReactivePubSubCommands<String> pubSub;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public RedisEventPublisher(ReactiveRedisDataSource redisDataSource) {
        this.pubSub = redisDataSource.pubsub(String.class);
    }

    public void publishLiquidation(Object liquidation) {
        publish("liquidation", liquidation);
    }

    public void publishOverview(Object overview) {
        publish("overview", overview);
    }

    private void publish(String type, Object data) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", type,
                    "data", data
            ));
            pubSub.publish(DERIVATIVES_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published %s to %d subscribers", type, count),
                            error -> LOG.errorf(error, "Failed to publish %s to Redis", type)
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize %s for Redis", type);
        }
    }
}
