package com.cryptoradar.news.client;

import com.cryptoradar.news.model.NewsArticle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fetches real crypto news from CoinDesk Data API (free, no key required).
 * Endpoint: https://data-api.coindesk.com/news/v1/article/list
 */
@ApplicationScoped
public class CryptoNewsClient {

    private static final Logger LOG = Logger.getLogger(CryptoNewsClient.class);

    private static final Map<String, String> NAME_TO_SYMBOL = Map.ofEntries(
            Map.entry("bitcoin", "BTC"), Map.entry("btc", "BTC"),
            Map.entry("ethereum", "ETH"), Map.entry("eth", "ETH"),
            Map.entry("binance", "BNB"), Map.entry("bnb", "BNB"),
            Map.entry("solana", "SOL"), Map.entry("sol", "SOL"),
            Map.entry("ripple", "XRP"), Map.entry("xrp", "XRP"),
            Map.entry("cardano", "ADA"), Map.entry("ada", "ADA"),
            Map.entry("avalanche", "AVAX"), Map.entry("avax", "AVAX"),
            Map.entry("polkadot", "DOT"), Map.entry("dot", "DOT"),
            Map.entry("chainlink", "LINK"), Map.entry("link", "LINK"),
            Map.entry("dogecoin", "DOGE"), Map.entry("doge", "DOGE")
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @ConfigProperty(name = "news.api.base-url")
    String baseUrl;

    @ConfigProperty(name = "news.api.articles-path")
    String articlesPath;

    public CryptoNewsClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<NewsArticle> fetchLatestNews() {
        try {
            String url = baseUrl + articlesPath + "?lang=EN&limit=20&sortOrder=latest";
            LOG.infof("Fetching news from CoinDesk API: %s", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("CoinDesk news API returned status %d", response.statusCode());
                return Collections.emptyList();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch news from CoinDesk API");
            return Collections.emptyList();
        }
    }

    private List<NewsArticle> parseResponse(String responseBody) {
        List<NewsArticle> articles = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("Data");

            if (data == null || !data.isArray()) {
                LOG.warn("No 'Data' array found in CoinDesk response");
                return articles;
            }

            for (JsonNode item : data) {
                try {
                    NewsArticle article = mapToArticle(item);
                    if (article != null) {
                        articles.add(article);
                    }
                } catch (Exception e) {
                    LOG.warnf("Failed to parse news item: %s", e.getMessage());
                }
            }

            LOG.infof("Parsed %d news articles from CoinDesk", articles.size());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse CoinDesk response");
        }

        return articles;
    }

    private NewsArticle mapToArticle(JsonNode item) {
        NewsArticle article = new NewsArticle();

        article.externalId = String.valueOf(item.get("ID").asLong());
        article.title = item.has("TITLE") ? item.get("TITLE").asText() : "";
        article.url = item.has("URL") ? item.get("URL").asText() : "";
        article.imageUrl = item.has("IMAGE_URL") ? item.get("IMAGE_URL").asText() : null;
        article.publishedAt = Instant.ofEpochSecond(item.get("PUBLISHED_ON").asLong());
        article.fetchedAt = Instant.now();

        // Body — truncate to first 500 chars for storage
        if (item.has("BODY") && !item.get("BODY").isNull()) {
            String body = item.get("BODY").asText();
            article.body = body.length() > 500 ? body.substring(0, 500) : body;
        }

        // Source — use AUTHORS or SOURCE_ID
        if (item.has("AUTHORS") && !item.get("AUTHORS").isNull()) {
            article.source = item.get("AUTHORS").asText();
        }
        // Override with source name from SOURCE_DATA if available
        if (item.has("SOURCE_DATA") && item.get("SOURCE_DATA").has("NAME")) {
            article.source = item.get("SOURCE_DATA").get("NAME").asText();
        }

        // Extract related symbols from title + categories
        String searchText = article.title.toLowerCase();
        if (item.has("CATEGORIES") && !item.get("CATEGORIES").isNull()) {
            searchText += " " + item.get("CATEGORIES").asText().toLowerCase();
        }
        article.setRelatedSymbols(extractSymbols(searchText));
        article.setCategories(parseCategories(item));

        return article;
    }

    private List<String> extractSymbols(String text) {
        List<String> symbols = new ArrayList<>();
        for (Map.Entry<String, String> entry : NAME_TO_SYMBOL.entrySet()) {
            if (text.contains(entry.getKey()) && !symbols.contains(entry.getValue())) {
                symbols.add(entry.getValue());
            }
        }
        return symbols;
    }

    private List<String> parseCategories(JsonNode item) {
        if (!item.has("CATEGORIES") || item.get("CATEGORIES").isNull()) {
            return List.of("Crypto");
        }
        String cats = item.get("CATEGORIES").asText();
        if (cats.isBlank()) return List.of("Crypto");

        List<String> result = new ArrayList<>();
        for (String cat : cats.split("\\|")) {
            String trimmed = cat.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? List.of("Crypto") : result;
    }
}
