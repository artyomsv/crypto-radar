package com.cryptoradar.marketdata.event;

import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class RedisEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedisEventPublisher.class);

    private static final String PRICES_CHANNEL = "crypto:prices";
    private static final String CANDLES_CHANNEL = "crypto:candles";
    private static final String ALERTS_CHANNEL = "crypto:alerts";

    private final ReactivePubSubCommands<String> pubSub;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public RedisEventPublisher(ReactiveRedisDataSource redisDataSource) {
        this.pubSub = redisDataSource.pubsub(String.class);
    }

    public void publishPriceUpdate(List<PriceSnapshot> prices) {
        try {
            String json = objectMapper.writeValueAsString(prices);
            pubSub.publish(PRICES_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published price update to %d subscribers", count),
                            error -> LOG.errorf(error, "Failed to publish price update to Redis")
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize price update for Redis");
        }
    }

    public void publishCandleUpdate(String symbol, List<Candle> candles) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "symbol", symbol,
                    "candles", candles
            ));
            pubSub.publish(CANDLES_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.debugf("Published candle update for %s to %d subscribers", symbol, count),
                            error -> LOG.errorf(error, "Failed to publish candle update to Redis")
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize candle update for Redis");
        }
    }

    public void publishAlert(Map<String, Object> alert) {
        try {
            String json = objectMapper.writeValueAsString(alert);
            pubSub.publish(ALERTS_CHANNEL, json)
                    .subscribe().with(
                            count -> LOG.infof("Published alert to %d subscribers: %s %s %.2f",
                                    count, alert.get("symbol"), alert.get("condition"), alert.get("targetPrice")),
                            error -> LOG.errorf(error, "Failed to publish alert to Redis")
                    );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize alert for Redis");
        }
    }
}
