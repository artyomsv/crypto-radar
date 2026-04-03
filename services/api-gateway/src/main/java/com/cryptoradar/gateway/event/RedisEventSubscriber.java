package com.cryptoradar.gateway.event;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RedisEventSubscriber {

    private static final Logger LOG = Logger.getLogger(RedisEventSubscriber.class);

    private static final String CHANNEL_PRICES = "crypto:prices";
    private static final String CHANNEL_NEWS = "crypto:news";
    private static final String CHANNEL_ANALYTICS = "crypto:analytics";

    @Inject
    ReactiveRedisDataSource redisDataSource;

    @Inject
    SseManager sseManager;

    void onStart(@Observes StartupEvent event) {
        // Delay subscription slightly to ensure Redis is fully ready
        Executors.newSingleThreadScheduledExecutor().schedule(this::subscribeToChannels, 3, TimeUnit.SECONDS);
    }

    private void subscribeToChannels() {
        LOG.info("Subscribing to Redis pub/sub channels...");

        try {
            ReactivePubSubCommands<String> pubsub = redisDataSource.pubsub(String.class);

            pubsub.subscribe(CHANNEL_PRICES)
                    .subscribe().with(
                            message -> {
                                LOG.debugf("Received price update from Redis");
                                sseManager.broadcastPrices(message);
                            },
                            failure -> {
                                LOG.errorf("Redis prices subscription failed: %s", failure.getMessage());
                                retrySubscription();
                            }
                    );

            pubsub.subscribe(CHANNEL_NEWS)
                    .subscribe().with(
                            message -> {
                                LOG.debugf("Received news update from Redis");
                                sseManager.broadcastNews(message);
                            },
                            failure -> LOG.errorf("Redis news subscription failed: %s", failure.getMessage())
                    );

            pubsub.subscribe(CHANNEL_ANALYTICS)
                    .subscribe().with(
                            message -> {
                                LOG.debugf("Received analytics update from Redis");
                                sseManager.broadcastAnalytics(message);
                            },
                            failure -> LOG.errorf("Redis analytics subscription failed: %s", failure.getMessage())
                    );

            LOG.info("Subscribed to Redis channels: " + CHANNEL_PRICES + ", " + CHANNEL_NEWS + ", " + CHANNEL_ANALYTICS);
        } catch (Exception e) {
            LOG.errorf("Failed to subscribe to Redis channels: %s. Will retry in 10s.", e.getMessage());
            retrySubscription();
        }
    }

    private void retrySubscription() {
        Executors.newSingleThreadScheduledExecutor().schedule(this::subscribeToChannels, 10, TimeUnit.SECONDS);
    }
}
