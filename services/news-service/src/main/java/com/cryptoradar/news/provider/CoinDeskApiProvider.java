package com.cryptoradar.news.provider;

import com.cryptoradar.news.model.NewsArticle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

/**
 * Fetches crypto news from CoinDesk Data API (free, no key required).
 * https://data-api.coindesk.com/news/v1/article/list
 */
@ApplicationScoped
public class CoinDeskApiProvider implements NewsProvider {

    private static final Logger LOG = Logger.getLogger(CoinDeskApiProvider.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "news.api.base-url", defaultValue = "https://data-api.coindesk.com")
    String baseUrl;

    @Override
    public String getSourceName() {
        return "CoinDesk API";
    }

    @Override
    public List<NewsArticle> fetchLatestArticles() {
        try {
            String url = baseUrl + "/news/v1/article/list?lang=EN&limit=20&sortOrder=latest";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("[%s] API returned %d", getSourceName(), response.statusCode());
                return Collections.emptyList();
            }

            JsonNode data = objectMapper.readTree(response.body()).get("Data");
            if (data == null || !data.isArray()) return Collections.emptyList();

            List<NewsArticle> articles = new ArrayList<>();
            for (JsonNode item : data) {
                articles.add(mapArticle(item));
            }
            LOG.infof("[%s] Fetched %d articles", getSourceName(), articles.size());
            return articles;

        } catch (Exception e) {
            LOG.errorf(e, "[%s] Fetch failed", getSourceName());
            return Collections.emptyList();
        }
    }

    private NewsArticle mapArticle(JsonNode item) {
        NewsArticle a = new NewsArticle();
        a.externalId = "coindesk-" + item.get("ID").asLong();
        a.title = item.path("TITLE").asText("");
        a.url = item.path("URL").asText("");
        a.imageUrl = item.path("IMAGE_URL").asText(null);
        a.publishedAt = Instant.ofEpochSecond(item.get("PUBLISHED_ON").asLong());
        a.fetchedAt = Instant.now();
        a.source = item.path("SOURCE_DATA").path("NAME").asText(
                item.path("AUTHORS").asText("CoinDesk"));

        String body = item.path("BODY").asText("");
        a.body = body.length() > 500 ? body.substring(0, 500) : body;

        String searchText = a.title + " " + item.path("CATEGORIES").asText("");
        a.setRelatedSymbols(SymbolExtractor.extract(searchText));
        a.setCategories(List.of("Crypto"));
        return a;
    }
}
