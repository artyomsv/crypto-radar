package com.cryptoradar.news.service;

import com.cryptoradar.news.client.CryptoCompareClient;
import com.cryptoradar.news.event.RedisEventPublisher;
import com.cryptoradar.news.model.DailySentiment;
import com.cryptoradar.news.model.NewsArticle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class NewsService {

    private static final Logger LOG = Logger.getLogger(NewsService.class);

    @Inject
    CryptoCompareClient cryptoCompareClient;

    @Inject
    SentimentAnalyzer sentimentAnalyzer;

    @Inject
    EntityManager entityManager;

    @Inject
    RedisEventPublisher redisEventPublisher;

    @Transactional
    public int fetchAndStoreNews() {
        List<NewsArticle> fetched = cryptoCompareClient.fetchLatestNews();
        List<NewsArticle> newArticles = new ArrayList<>();

        for (NewsArticle article : fetched) {
            // Deduplicate by externalId
            NewsArticle existing = NewsArticle.findByExternalId(article.externalId);
            if (existing != null) {
                continue;
            }

            // Run sentiment analysis
            SentimentResult sentiment = sentimentAnalyzer.analyze(article.title, article.body);
            article.sentimentScore = sentiment.score();
            article.sentimentLabel = sentiment.label();
            article.fetchedAt = Instant.now();

            article.persist();
            newArticles.add(article);
        }

        if (!newArticles.isEmpty()) {
            LOG.infof("Stored %d new articles (out of %d fetched)", newArticles.size(), fetched.size());
            redisEventPublisher.publishNewsUpdate(newArticles);
        } else {
            LOG.info("No new articles to store");
        }

        return newArticles.size();
    }

    public List<NewsArticle> getNewsBySymbol(String symbol, int limit) {
        return NewsArticle.findBySymbol(symbol.toUpperCase(), limit);
    }

    public List<NewsArticle> getLatestNews(int limit) {
        return NewsArticle.findLatest(limit);
    }

    public Map<String, Object> getSentimentBySymbol(String symbol) {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        String upperSymbol = symbol.toUpperCase();

        @SuppressWarnings("unchecked")
        List<NewsArticle> recentArticles = entityManager.createQuery(
                        "SELECT n FROM NewsArticle n WHERE n.relatedSymbolsRaw LIKE :symbol " +
                                "AND n.publishedAt >= :since ORDER BY n.publishedAt DESC"
                )
                .setParameter("symbol", "%" + upperSymbol + "%")
                .setParameter("since", sevenDaysAgo)
                .getResultList();

        int positiveCount = 0;
        int negativeCount = 0;
        int neutralCount = 0;
        double totalScore = 0.0;

        for (NewsArticle article : recentArticles) {
            if (article.sentimentScore != null) {
                totalScore += article.sentimentScore;
            }
            if ("positive".equals(article.sentimentLabel)) {
                positiveCount++;
            } else if ("negative".equals(article.sentimentLabel)) {
                negativeCount++;
            } else {
                neutralCount++;
            }
        }

        int total = recentArticles.size();
        double avgSentiment = total > 0 ? totalScore / total : 0.0;

        Map<String, Object> result = new HashMap<>();
        result.put("symbol", upperSymbol);
        result.put("period", "7d");
        result.put("totalArticles", total);
        result.put("avgSentiment", Math.round(avgSentiment * 10000.0) / 10000.0);
        result.put("positiveCount", positiveCount);
        result.put("negativeCount", negativeCount);
        result.put("neutralCount", neutralCount);

        String overallLabel;
        if (avgSentiment > 0.05) {
            overallLabel = "positive";
        } else if (avgSentiment < -0.05) {
            overallLabel = "negative";
        } else {
            overallLabel = "neutral";
        }
        result.put("overallSentiment", overallLabel);

        return result;
    }

    @SuppressWarnings("unchecked")
    public List<DailySentiment> getDailySentiment(String symbol, int days) {
        String upperSymbol = symbol.toUpperCase();
        return entityManager.createQuery(
                        "SELECT d FROM DailySentiment d WHERE d.symbol = :symbol " +
                                "AND d.date >= :since ORDER BY d.date DESC"
                )
                .setParameter("symbol", upperSymbol)
                .setParameter("since", java.time.LocalDate.now().minusDays(days))
                .getResultList();
    }
}
