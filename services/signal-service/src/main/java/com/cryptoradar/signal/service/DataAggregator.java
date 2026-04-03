package com.cryptoradar.signal.service;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches data from all upstream services via REST.
 * Results are cached in memory with a 30s TTL.
 */
@ApplicationScoped
public class DataAggregator {

    private static final Logger LOG = Logger.getLogger(DataAggregator.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_TTL_MS = 30_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String marketDataUrl;
    private final String analyticsUrl;
    private final String whaleUrl;
    private final String derivativesUrl;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DataAggregator(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "market-data.url") String marketDataUrl,
            @ConfigProperty(name = "analytics.url") String analyticsUrl,
            @ConfigProperty(name = "whale.url") String whaleUrl,
            @ConfigProperty(name = "derivatives.url") String derivativesUrl) {
        this.objectMapper = objectMapper;
        this.marketDataUrl = marketDataUrl;
        this.analyticsUrl = analyticsUrl;
        this.whaleUrl = whaleUrl;
        this.derivativesUrl = derivativesUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchAnalytics(String symbol) {
        return (Map<String, Object>) fetchCached(
                "analytics:" + symbol,
                analyticsUrl + "/api/analytics/" + symbol);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchMarketOverview() {
        return (Map<String, Object>) fetchCached(
                "market-overview",
                analyticsUrl + "/api/analytics/market-overview");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchWhaleAnalytics() {
        return (Map<String, Object>) fetchCached(
                "whale-analytics",
                whaleUrl + "/api/whales/analytics");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchDerivativesOverview() {
        return (Map<String, Object>) fetchCached(
                "derivatives-overview",
                derivativesUrl + "/api/derivatives/overview");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPrices() {
        return (List<Map<String, Object>>) fetchCached(
                "prices",
                marketDataUrl + "/api/market/prices");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchMacro() {
        return (Map<String, Object>) fetchCached(
                "macro",
                analyticsUrl + "/api/analytics/macro");
    }

    private Object fetchCached(String cacheKey, String url) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }

        Object result = fetchFromService(url);
        if (result != null) {
            cache.put(cacheKey, new CacheEntry(result));
        }
        return result;
    }

    private Object fetchFromService(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warnf("Non-200 response from %s: %d", url, response.statusCode());
                return null;
            }

            return objectMapper.readValue(response.body(), Object.class);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch %s: %s", url, e.getMessage());
            return null;
        }
    }

    private record CacheEntry(Object value, Instant fetchedAt) {
        CacheEntry(Object value) {
            this(value, Instant.now());
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() - fetchedAt.toEpochMilli() > CACHE_TTL_MS;
        }
    }
}
