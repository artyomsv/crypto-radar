package com.cryptoradar.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal client to market-data-service — fetches the last-known price for a
 * symbol. Used by TrailMirror to compute cumulative MFE.
 */
@ApplicationScoped
public class MarketDataClient {

    private static final Logger LOG = Logger.getLogger(MarketDataClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "market-data.url")
    String baseUrl;

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
}
