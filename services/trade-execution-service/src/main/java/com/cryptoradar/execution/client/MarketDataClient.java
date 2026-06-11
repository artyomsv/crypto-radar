package com.cryptoradar.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
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
 * Minimal client to market-data-service — fetches the last-known price for a
 * symbol. Used by TrailMirror to compute cumulative MFE.
 */
@ApplicationScoped
public class MarketDataClient {

    private static final Logger LOG = Logger.getLogger(MarketDataClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    /** CDI constructor — wired by Quarkus via constructor injection. */
    @jakarta.inject.Inject
    public MarketDataClient(
            @ConfigProperty(name = "market-data.url") String baseUrl,
            ObjectMapper mapper) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    /** Test-only: parse helpers don't need HTTP/config. */
    MarketDataClient() {
        this.mapper = new ObjectMapper();
        this.http = null;
        this.baseUrl = null;
    }

    /** Compact daily OHLC bar for Donchian channel computation. */
    public record DailyBar(Instant time, double open, double high, double low, double close) {}

    public BigDecimal getLastPrice(String symbol) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/market/prices"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("market-data HTTP %d for %s", resp.statusCode(), symbol);
                return null;
            }
            return parsePrice(resp.body(), symbol);
        } catch (IOException e) {
            LOG.warnf(e, "market-data fetch failed for %s", symbol);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf(e, "market-data fetch interrupted for %s", symbol);
            return null;
        } catch (RuntimeException e) {
            LOG.warnf(e, "market-data parse failed for %s", symbol);
            return null;
        }
    }

    /**
     * Parse the price for {@code symbol} out of a market-data response body.
     * The endpoint returns an ARRAY of {@code {symbol, price, ...}} objects
     * (not a symbol-keyed object as the legacy code assumed). The legacy
     * shape mismatch silently returned null for every lookup, leaving the
     * v2/v3/v4 trail system inert in production — every closed trade
     * resolved as TARGET-or-INITIAL_STOP regardless of MFE.
     */
    BigDecimal parsePrice(String body, String symbol) {
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) {
                LOG.warnf("market-data shape unexpected for %s: %s", symbol, root.getNodeType());
                return null;
            }
            for (JsonNode entry : root) {
                if (!symbol.equals(entry.path("symbol").asText(null))) continue;
                JsonNode price = entry.path("price");
                if (price.isMissingNode() || price.isNull()) return null;
                return new BigDecimal(price.asText());
            }
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.warnf(e, "market-data parse failed for %s", symbol);
            return null;
        }
    }

    /**
     * Fetch the most recent {@code limit} daily candles for the symbol, oldest
     * first. Empty on any failure (fail-open: the caller skips this tick).
     * Mirrors signal-service CandleClient's endpoint + ordering.
     */
    public List<DailyBar> getDailyCandles(String symbol, int limit) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    baseUrl + "/api/market/candles/" + symbol + "?interval=1d&limit=" + limit))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("market-data 1d HTTP %d for %s", resp.statusCode(), symbol);
                return List.of();
            }
            return parseDailyBars(resp.body());
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOG.warnf(e, "market-data 1d fetch failed for %s", symbol);
            return List.of();
        }
    }

    /** Parse the DESC-by-time JSON array into oldest-first DailyBars. Pure; package-private for testing. */
    List<DailyBar> parseDailyBars(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) return List.of();
            List<DailyBar> bars = new ArrayList<>(root.size());
            for (JsonNode e : root) {
                JsonNode t = e.path("time");
                if (t.isMissingNode() || t.isNull()) continue;
                bars.add(new DailyBar(
                        Instant.parse(t.asText()),
                        e.path("open").asDouble(), e.path("high").asDouble(),
                        e.path("low").asDouble(), e.path("close").asDouble()));
            }
            Collections.reverse(bars); // upstream DESC -> oldest-first
            return bars;
        } catch (RuntimeException | IOException e) {
            return List.of();
        }
    }
}
