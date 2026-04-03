package com.cryptoradar.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class FearGreedClient {

    private static final Logger LOG = Logger.getLogger(FearGreedClient.class);
    private static final String API_URL = "https://api.alternative.me/fng/?limit=1";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    ObjectMapper objectMapper;

    // Cached values (API updates once per day)
    private volatile int cachedIndex = -1;
    private volatile String cachedLabel = "Unknown";
    private volatile long lastFetchTime = 0;
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    public int getIndex() {
        refreshIfNeeded();
        return cachedIndex;
    }

    public String getLabel() {
        refreshIfNeeded();
        return cachedLabel;
    }

    private void refreshIfNeeded() {
        if (System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS && cachedIndex >= 0) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Fear & Greed API returned %d", response.statusCode());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode entry = data.get(0);
                cachedIndex = entry.get("value").asInt();
                cachedLabel = entry.get("value_classification").asText();
                lastFetchTime = System.currentTimeMillis();
                LOG.infof("Fear & Greed Index: %d (%s)", cachedIndex, cachedLabel);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to fetch Fear & Greed Index: %s", e.getMessage());
        }
    }
}
