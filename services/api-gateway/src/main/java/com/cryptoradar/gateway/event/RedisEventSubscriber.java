package com.cryptoradar.gateway.event;

import com.cryptoradar.gateway.websocket.WebSocketBroadcaster;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to Redis pub/sub using Vert.x Redis client directly.
 * Forwards messages to WebSocket broadcaster for real-time client updates.
 */
@ApplicationScoped
public class RedisEventSubscriber {

    private static final Logger LOG = Logger.getLogger(RedisEventSubscriber.class);

    @Inject
    Vertx vertx;

    @Inject
    WebSocketBroadcaster broadcaster;

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    String redisHosts;

    void onStart(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor().schedule(this::connect, 3, TimeUnit.SECONDS);
    }

    private void connect() {
        LOG.info("Connecting to Redis for pub/sub...");

        RedisOptions options = new RedisOptions().setConnectionString(redisHosts);

        Redis.createClient(vertx, options)
                .connect()
                .subscribe().with(
                        conn -> {
                            LOG.info("Redis pub/sub connection established");
                            setupSubscription(conn);
                        },
                        error -> {
                            LOG.errorf("Failed to connect to Redis: %s. Retrying in 10s...", error.getMessage());
                            Executors.newSingleThreadScheduledExecutor()
                                    .schedule(this::connect, 10, TimeUnit.SECONDS);
                        }
                );
    }

    private void setupSubscription(RedisConnection conn) {
        // Handle incoming messages
        conn.handler(message -> {
            if (message == null || message.size() < 3) return;

            String type = message.get(0).toString();
            if (!"message".equals(type)) return;

            String channel = message.get(1).toString();
            String payload = message.get(2).toString();

            switch (channel) {
                case "crypto:prices" -> {
                    LOG.debugf("Redis → WS broadcast: prices (%d chars)", payload.length());
                    broadcaster.broadcastPrices(payload);
                }
                case "crypto:news" -> {
                    LOG.debugf("Redis → WS broadcast: news (%d chars)", payload.length());
                    broadcaster.broadcastNews(payload);
                }
                case "crypto:analytics" -> {
                    LOG.debugf("Redis → WS broadcast: analytics (%d chars)", payload.length());
                    broadcaster.broadcastAnalytics(payload);
                }
            }
        });

        // Handle connection exceptions
        conn.exceptionHandler(err -> {
            LOG.warnf("Redis pub/sub connection error: %s. Reconnecting in 5s...", err.getMessage());
            Executors.newSingleThreadScheduledExecutor()
                    .schedule(this::connect, 5, TimeUnit.SECONDS);
        });

        // Subscribe to all channels
        RedisAPI api = RedisAPI.api(conn);
        api.subscribe(List.of("crypto:prices", "crypto:news", "crypto:analytics"))
                .subscribe().with(
                        response -> LOG.info("Subscribed to Redis channels: crypto:prices, crypto:news, crypto:analytics"),
                        error -> {
                            LOG.errorf("Failed to subscribe to Redis channels: %s", error.getMessage());
                            conn.close();
                        }
                );
    }
}
