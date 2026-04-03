package com.cryptoradar.whale.event;

import com.cryptoradar.whale.model.WhaleMarketOverview;
import com.cryptoradar.whale.model.WhaleTransaction;
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
    private static final String WHALE_CHANNEL = "crypto:whales";

    private final ReactivePubSubCommands<String> pubSub;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public RedisEventPublisher(ReactiveRedisDataSource redisDataSource) {
        this.pubSub = redisDataSource.pubsub(String.class);
    }

    public void publishWhaleTransaction(WhaleTransaction tx) {
        try {
            Map<String, Object> message = Map.of(
                    "type", "transaction",
                    "data", tx
            );
            String json = objectMapper.writeValueAsString(message);
            pubSub.publish(WHALE_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published whale tx %s %s $%.0f to %d subscribers",
                                    tx.getSymbol(), tx.getSide(), tx.getValueUsd(), count),
                            error -> LOG.errorf(error, "Failed to publish whale tx for %s to Redis",
                                    tx.getSymbol())
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize whale transaction for %s", tx.getSymbol());
        }
    }

    public void publishMarketOverview(WhaleMarketOverview overview) {
        try {
            Map<String, Object> message = Map.of(
                    "type", "market_overview",
                    "data", overview
            );
            String json = objectMapper.writeValueAsString(message);
            pubSub.publish(WHALE_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published whale market overview to %d subscribers", count),
                            error -> LOG.errorf(error, "Failed to publish whale market overview to Redis")
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize whale market overview");
        }
    }
}
