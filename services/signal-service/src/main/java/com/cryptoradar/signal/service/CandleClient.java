package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * Fetches 1m candles from market-data-service for outcome evaluation.
 *
 * <p>Intentionally separate from {@link DataAggregator} because the evaluator
 * needs fresh (uncached) candle reads whereas {@link DataAggregator} caches
 * for 30 seconds. Also: this client returns candles ordered oldest-first,
 * which is the natural iteration order for walking price forward in time.
 */
@ApplicationScoped
public class CandleClient {

    private static final Logger LOG = Logger.getLogger(CandleClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String marketDataUrl;

    public CandleClient(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "market-data.url") String marketDataUrl) {
        this.objectMapper = objectMapper;
        this.marketDataUrl = marketDataUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Fetches the most recent {@code limit} candles of the given interval for
     * the symbol, returned ordered oldest-first. Returns an empty list on
     * failure — callers should treat that as "no new price data, try again
     * next tick" rather than an error.
     */
    public List<CandleBar> fetchRecent(String symbol, String interval, int limit) {
        String url = buildUrl(symbol, interval, limit);
        try {
            HttpResponse<String> response = sendGet(url);
            if (response.statusCode() != 200) {
                LOG.warnf("Non-200 from candles endpoint %s: %d", url, response.statusCode());
                return List.of();
            }
            return parseBars(response.body());
        } catch (Exception e) {
            LOG.warnf("Failed to fetch candles %s %s: %s", symbol, interval, e.getMessage());
            return List.of();
        }
    }

    private String buildUrl(String symbol, String interval, int limit) {
        return marketDataUrl
                + "/api/market/candles/" + symbol
                + "?interval=" + interval
                + "&limit=" + limit;
    }

    private HttpResponse<String> sendGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<CandleBar> parseBars(String json) throws Exception {
        List<Map<String, Object>> rows = objectMapper.readValue(json, new TypeReference<>() {});
        List<CandleBar> bars = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            CandleBar bar = toBar(row);
            if (bar != null) bars.add(bar);
        }
        // Upstream returns DESC by time; reverse so callers can walk forward.
        Collections.reverse(bars);
        return bars;
    }

    private CandleBar toBar(Map<String, Object> row) {
        Object timeObj = row.get("time");
        Object openObj = row.get("open");
        Object highObj = row.get("high");
        Object lowObj = row.get("low");
        Object closeObj = row.get("close");
        if (timeObj == null || openObj == null || highObj == null || lowObj == null || closeObj == null) {
            return null;
        }
        Instant time = Instant.parse(timeObj.toString());
        double open = ((Number) openObj).doubleValue();
        double high = ((Number) highObj).doubleValue();
        double low = ((Number) lowObj).doubleValue();
        double close = ((Number) closeObj).doubleValue();
        return new CandleBar(time, open, high, low, close);
    }
}
