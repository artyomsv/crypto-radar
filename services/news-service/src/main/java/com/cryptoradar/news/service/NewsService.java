package com.cryptoradar.news.service;

import com.cryptoradar.news.event.RedisEventPublisher;
import com.cryptoradar.news.model.DailySentiment;
import com.cryptoradar.news.model.NewsArticle;
import com.cryptoradar.news.provider.NewsAggregator;
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
    NewsAggregator newsAggregator;

    @Inject
    SentimentAnalyzer sentimentAnalyzer;

    @Inject
    EntityManager entityManager;

    @Inject
    RedisEventPublisher redisEventPublisher;

    @Transactional
    public int fetchAndStoreNews() {
        List<NewsArticle> fetched = newsAggregator.fetchFromAllSources();
        List<NewsArticle> newArticles = new ArrayList<>();

        for (NewsArticle article : fetched) {
            try {
                // Deduplicate by externalId or URL
                NewsArticle existing = NewsArticle.findByExternalId(article.externalId);
                if (existing != null) {
                    continue;
                }
                if (article.url != null) {
                    long urlCount = NewsArticle.count("url", article.url);
                    if (urlCount > 0) continue;
                }

                // Run sentiment analysis
                SentimentResult sentiment = sentimentAnalyzer.analyze(article.title, article.body);
                article.sentimentScore = sentiment.score();
                article.sentimentLabel = sentiment.label();
                article.fetchedAt = Instant.now();

                article.persist();
                entityManager.flush();
                newArticles.add(article);
            } catch (Exception e) {
                // Skip duplicates from race conditions
                entityManager.clear();
                LOG.debugf("Skipped duplicate article: %s", article.externalId);
            }
        }

        if (!newArticles.isEmpty()) {
            LOG.infof("Stored %d new articles (out of %d fetched)", newArticles.size(), fetched.size());
            redisEventPublisher.publishNewsUpdate(newArticles);
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
        // Articles are tagged with base tickers (BTC, ETH) from provider metadata,
        // but callers pass trading pairs (BTCUSDT). Strip the quote suffix before
        // matching. Also use word-boundary LIKE to avoid matching "BTC" inside a
        // larger token if the column ever contains names like "BTCASH".
        String upperSymbol = symbol.toUpperCase();
        String baseSymbol = stripQuoteSuffix(upperSymbol);

        @SuppressWarnings("unchecked")
        List<NewsArticle> recentArticles = entityManager.createQuery(
                        "SELECT n FROM NewsArticle n WHERE ("
                                + "  n.relatedSymbolsRaw = :exact"
                                + "  OR n.relatedSymbolsRaw LIKE :leading ESCAPE '\\'"
                                + "  OR n.relatedSymbolsRaw LIKE :trailing ESCAPE '\\'"
                                + "  OR n.relatedSymbolsRaw LIKE :middle ESCAPE '\\'"
                                + ") AND n.publishedAt >= :since ORDER BY n.publishedAt DESC"
                )
                .setParameter("exact", baseSymbol)
                .setParameter("leading", baseSymbol + ",%")
                .setParameter("trailing", "%," + baseSymbol)
                .setParameter("middle", "%," + baseSymbol + ",%")
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

    // Trading-pair suffixes the signal engine queries with (BTCUSDT, ETHBUSD etc.).
    // News articles are tagged with the base ticker (BTC, ETH) from provider
    // categories, so these suffixes must come off before matching.
    private static final List<String> QUOTE_SUFFIXES = List.of(
            "USDT", "USDC", "BUSD", "TUSD", "DAI", "USD");

    static String stripQuoteSuffix(String upperSymbol) {
        if (upperSymbol == null) return null;
        for (String suffix : QUOTE_SUFFIXES) {
            if (upperSymbol.endsWith(suffix) && upperSymbol.length() > suffix.length()) {
                return upperSymbol.substring(0, upperSymbol.length() - suffix.length());
            }
        }
        return upperSymbol;
    }

    @SuppressWarnings("unchecked")
    public List<DailySentiment> getDailySentiment(String symbol, int days) {
        // Same suffix handling as getSentimentBySymbol — callers pass
        // trading pairs, daily rows are keyed by base ticker.
        String baseSymbol = stripQuoteSuffix(symbol.toUpperCase());
        return entityManager.createQuery(
                        "SELECT d FROM DailySentiment d WHERE d.symbol = :symbol " +
                                "AND d.date >= :since ORDER BY d.date DESC"
                )
                .setParameter("symbol", baseSymbol)
                .setParameter("since", java.time.LocalDate.now().minusDays(days))
                .getResultList();
    }
}
