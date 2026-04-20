package com.cryptoradar.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class ServiceClient {

    private static final Logger LOG = Logger.getLogger(ServiceClient.class);

    @ConfigProperty(name = "market-data.url")
    String marketDataUrl;

    @ConfigProperty(name = "news-service.url")
    String newsServiceUrl;

    @ConfigProperty(name = "analytics-service.url")
    String analyticsServiceUrl;

    @ConfigProperty(name = "whale-service.url")
    String whaleServiceUrl;

    @ConfigProperty(name = "derivatives-service.url", defaultValue = "http://localhost:8085")
    String derivativesServiceUrl;

    @ConfigProperty(name = "signal-service.url", defaultValue = "http://localhost:8086")
    String signalServiceUrl;

    @ConfigProperty(name = "execution.url")
    String executionUrl;

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public JsonNode getPrices() {
        return get(marketDataUrl + "/api/market/prices");
    }

    public JsonNode getAssets() {
        return get(marketDataUrl + "/api/market/assets");
    }

    public JsonNode getCandles(String symbol, String interval, int limit) {
        String url = marketDataUrl + "/api/market/candles/" + symbol
                + "?interval=" + interval + "&limit=" + limit;
        return get(url);
    }

    public JsonNode getLatestNews(int limit) {
        return get(newsServiceUrl + "/api/news/latest?limit=" + limit);
    }

    public JsonNode getNewsBySymbol(String symbol, int limit) {
        return get(newsServiceUrl + "/api/news?symbol=" + symbol + "&limit=" + limit);
    }

    public JsonNode getSentiment(String symbol) {
        return get(newsServiceUrl + "/api/news/sentiment/" + symbol);
    }

    public JsonNode getSentimentHistory(String symbol, int days) {
        return get(newsServiceUrl + "/api/news/sentiment/" + symbol + "/history?days=" + days);
    }

    public JsonNode getAnalysis(String symbol) {
        return get(analyticsServiceUrl + "/api/analytics/" + symbol);
    }

    public JsonNode getMarketOverview() {
        return get(analyticsServiceUrl + "/api/analytics/market-overview");
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readTree(response.body());
            }

            LOG.warnf("Non-2xx response from %s: %d", url, response.statusCode());
            return null;
        } catch (Exception e) {
            LOG.errorf("Failed to call %s: %s", url, e.getMessage());
            return null;
        }
    }

    /**
     * Raw GET returning the response body as a String, for proxy endpoints.
     */
    public String getRaw(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }

            LOG.warnf("Non-2xx response from %s: %d", url, response.statusCode());
            return null;
        } catch (Exception e) {
            LOG.errorf("Failed to call %s: %s", url, e.getMessage());
            return null;
        }
    }

    public String postRaw(String url, String body) {
        return sendWithBody(url, "POST", body);
    }

    public String putRaw(String url, String body) {
        return sendWithBody(url, "PUT", body);
    }

    public String patchRaw(String url, String body) {
        return sendWithBody(url, "PATCH", body);
    }

    public String deleteRaw(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .DELETE().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 300 ? response.body() : null;
        } catch (Exception e) {
            LOG.errorf("DELETE %s failed: %s", url, e.getMessage());
            return null;
        }
    }

    private String sendWithBody(String url, String method, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 300 ? response.body() : response.body();
        } catch (Exception e) {
            LOG.errorf("%s %s failed: %s", method, url, e.getMessage());
            return null;
        }
    }

    public String getMarketDataUrl() {
        return marketDataUrl;
    }

    public String getNewsServiceUrl() {
        return newsServiceUrl;
    }

    public String getAnalyticsServiceUrl() {
        return analyticsServiceUrl;
    }

    public String getWhaleServiceUrl() {
        return whaleServiceUrl;
    }

    public String getDerivativesServiceUrl() {
        return derivativesServiceUrl;
    }

    public String getSignalServiceUrl() {
        return signalServiceUrl;
    }

    public String getExecutionUrl() {
        return executionUrl;
    }

    public JsonNode getWhaleTransactions(String symbol, int limit) {
        String url = whaleServiceUrl + "/api/whales/transactions?limit=" + limit;
        if (symbol != null && !symbol.isEmpty()) {
            url += "&symbol=" + symbol;
        }
        return get(url);
    }

    public JsonNode getWhaleAnalytics() {
        return get(whaleServiceUrl + "/api/whales/analytics");
    }

    public JsonNode getWhaleFlow(String symbol, String window) {
        String url = whaleServiceUrl + "/api/whales/flow/" + symbol;
        if (window != null && !window.isEmpty()) {
            url += "?window=" + window;
        }
        return get(url);
    }
}
