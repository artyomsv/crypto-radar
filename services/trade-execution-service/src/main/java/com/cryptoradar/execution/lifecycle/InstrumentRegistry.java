package com.cryptoradar.execution.lifecycle;

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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches per-symbol qty step ("lot size") fetched from Bybit's public
 * /v5/market/instruments-info endpoint. No auth required.
 *
 * <p>Falls back to a static table when the HTTP fetch fails so a flaky
 * network at startup does not block order placement for known symbols.
 */
@ApplicationScoped
public class InstrumentRegistry {

    private static final Logger LOG = Logger.getLogger(InstrumentRegistry.class);

    /** Fallback qty steps for the 13-symbol watchlist. Authoritative source is Bybit. */
    private static final Map<String, Double> FALLBACK_QTY_STEP = Map.ofEntries(
            Map.entry("BTCUSDT", 0.001),
            Map.entry("ETHUSDT", 0.01),
            Map.entry("SOLUSDT", 0.1),
            Map.entry("BNBUSDT", 0.01),
            Map.entry("XRPUSDT", 1.0),
            Map.entry("DOGEUSDT", 1.0),
            Map.entry("LINKUSDT", 0.1),
            Map.entry("AVAXUSDT", 0.1),
            Map.entry("LTCUSDT", 0.01),
            Map.entry("DOTUSDT", 0.1),
            Map.entry("NEARUSDT", 0.1),
            Map.entry("ADAUSDT", 1.0),
            Map.entry("MATICUSDT", 1.0)
    );

    private static final double DEFAULT_UNKNOWN_QTY_STEP = 0.001;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    /** Cache key = env + "|" + symbol. */
    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @ConfigProperty(name = "bybit.rest-base-override.DEMO", defaultValue = "https://api-demo.bybit.com")
    String demoBase;

    @ConfigProperty(name = "bybit.rest-base-override.MAINNET", defaultValue = "https://api.bybit.com")
    String mainnetBase;

    public double qtyStepFor(String environment, String symbol) {
        String key = environment + "|" + symbol;
        Double cached = cache.get(key);
        if (cached != null) return cached;
        Optional<Double> fetched = fetchQtyStep(environment, symbol);
        double resolved = fetched.orElseGet(
                () -> FALLBACK_QTY_STEP.getOrDefault(symbol, DEFAULT_UNKNOWN_QTY_STEP));
        cache.put(key, resolved);
        if (fetched.isEmpty()) {
            LOG.warnf("InstrumentRegistry: falling back to static qtyStep for %s = %s", symbol, resolved);
        }
        return resolved;
    }

    /** Visible for tests — flush cache between cases. */
    public void invalidate() {
        cache.clear();
    }

    private Optional<Double> fetchQtyStep(String environment, String symbol) {
        String base = "MAINNET".equals(environment) ? mainnetBase : demoBase;
        URI uri = URI.create(base + "/v5/market/instruments-info?category=linear&symbol=" + symbol);
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("instruments-info HTTP %d for %s", resp.statusCode(), symbol);
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root.path("retCode").asInt(-1) != 0) {
                LOG.warnf("instruments-info retCode=%d retMsg=%s for %s",
                        root.path("retCode").asInt(-1),
                        root.path("retMsg").asText("?"), symbol);
                return Optional.empty();
            }
            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                return Optional.empty();
            }
            String step = list.get(0).path("lotSizeFilter").path("qtyStep").asText("");
            if (step.isEmpty()) return Optional.empty();
            return Optional.of(Double.parseDouble(step));
        } catch (Exception e) {
            LOG.warnf("instruments-info fetch failed for %s: %s", symbol, e.getMessage());
            return Optional.empty();
        }
    }
}
