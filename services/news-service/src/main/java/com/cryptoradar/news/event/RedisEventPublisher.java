package com.cryptoradar.news.event;

import com.cryptoradar.news.model.NewsArticle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class RedisEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedisEventPublisher.class);
    private static final String CHANNEL = "crypto:news";

    private final PubSubCommands<String> pubSub;
    private final ObjectMapper objectMapper;

    @Inject
    public RedisEventPublisher(RedisDataSource redisDataSource) {
        this.pubSub = redisDataSource.pubsub(String.class);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void publishNewsUpdate(List<NewsArticle> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        try {
            for (NewsArticle article : articles) {
                String json = objectMapper.writeValueAsString(new NewsEvent(
                        article.externalId,
                        article.title,
                        article.url,
                        article.source,
                        article.publishedAt != null ? article.publishedAt.toString() : null,
                        article.sentimentScore,
                        article.sentimentLabel,
                        article.relatedSymbols
                ));
                pubSub.publish(CHANNEL, json);
            }
            LOG.infof("Published %d news articles to Redis channel '%s'", articles.size(), CHANNEL);
        } catch (Exception e) {
            LOG.error("Failed to publish news to Redis", e);
        }
    }

    private record NewsEvent(
            String externalId,
            String title,
            String url,
            String source,
            String publishedAt,
            Double sentimentScore,
            String sentimentLabel,
            List<String> relatedSymbols
    ) {
    }
}
