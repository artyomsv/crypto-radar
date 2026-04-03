package com.cryptoradar.derivatives.client;

import com.cryptoradar.derivatives.model.FundingRate;
import com.cryptoradar.derivatives.model.LongShortRatio;
import com.cryptoradar.derivatives.model.OpenInterest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class BinanceFuturesClient {

    private static final Logger LOG = Logger.getLogger(BinanceFuturesClient.class);

    private static final Set<String> DEFAULT_SYMBOLS = Set.of(
            "BTCUSDT", "ETHUSDT", "XRPUSDT", "BNBUSDT", "SOLUSDT",
            "TRXUSDT", "DOGEUSDT", "BCHUSDT", "ADAUSDT", "LINKUSDT",
            "XMRUSDT", "XLMUSDT", "LTCUSDT", "ZECUSDT"
    );

    private static final int MAX_REQUESTS_PER_SECOND = 10;
    private static final long SYMBOL_CACHE_TTL_MS = 60_000;

    private volatile Set<String> cachedSymbols;
    private volatile long lastSymbolRefresh = 0;

    // Simple rate limiter: track requests per second
    private final AtomicInteger requestsThisSecond = new AtomicInteger(0);
    private final AtomicLong currentSecond = new AtomicLong(0);

    // Cache mark prices from funding rate calls for OI USD calculation
    private final Map<String, Double> markPriceCache = new HashMap<>();

    private final HttpClient httpClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "binance.futures.base-url")
    String baseUrl;

    public BinanceFuturesClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Fetch funding rates for ALL futures symbols (single API call, weight 1).
     * Filters to tracked symbols and caches mark prices for OI calculation.
     */
    public List<FundingRate> fetchFundingRates() {
        rateLimit();
        try {
            String url = baseUrl + "/fapi/v1/premiumIndex";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Binance premiumIndex API returned %d: %s",
                        response.statusCode(), response.body());
                return Collections.emptyList();
            }

            return parseFundingRates(response.body());

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch funding rates");
            return Collections.emptyList();
        }
    }

    /**
     * Fetch open interest for a single symbol (weight 1).
     * Uses cached mark price to compute USD value.
     */
    public OpenInterest fetchOpenInterest(String symbol) {
        rateLimit();
        try {
            String url = String.format("%s/fapi/v1/openInterest?symbol=%s", baseUrl, symbol);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Binance openInterest API returned %d for %s: %s",
                        response.statusCode(), symbol, response.body());
                return null;
            }

            return parseOpenInterest(response.body(), symbol);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch open interest for %s", symbol);
            return null;
        }
    }

    /**
     * Fetch global long/short account ratio for a symbol (weight 1).
     */
    public LongShortRatio fetchLongShortRatio(String symbol) {
        rateLimit();
        try {
            String url = String.format(
                    "%s/futures/data/globalLongShortAccountRatio?symbol=%s&period=5m&limit=1",
                    baseUrl, symbol);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Binance longShortRatio API returned %d for %s: %s",
                        response.statusCode(), symbol, response.body());
                return null;
            }

            return parseLongShortRatio(response.body(), symbol);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch long/short ratio for %s", symbol);
            return null;
        }
    }

    /**
     * Get cached mark price for a symbol (populated by fetchFundingRates).
     */
    public double getMarkPrice(String symbol) {
        return markPriceCache.getOrDefault(symbol, 0.0);
    }

    /**
     * Dynamic symbol list from DB with 60s cache, fallback to hardcoded defaults.
     */
    public Set<String> getTrackedSymbols() {
        long now = System.currentTimeMillis();
        if (cachedSymbols != null && now - lastSymbolRefresh < SYMBOL_CACHE_TTL_MS) {
            return cachedSymbols;
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT symbol FROM crypto_assets WHERE is_active = true");
             var rs = stmt.executeQuery()) {
            Set<String> symbols = new HashSet<>();
            while (rs.next()) {
                symbols.add(rs.getString("symbol"));
            }
            if (!symbols.isEmpty()) {
                cachedSymbols = symbols;
                lastSymbolRefresh = now;
                return symbols;
            }
        } catch (Exception e) {
            LOG.debugf("Failed to load symbols from DB, using defaults: %s", e.getMessage());
        }
        return DEFAULT_SYMBOLS;
    }

    private List<FundingRate> parseFundingRates(String body) {
        List<FundingRate> rates = new ArrayList<>();
        Set<String> tracked = getTrackedSymbols();
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode node : root) {
                String symbol = node.get("symbol").asText();
                if (!tracked.contains(symbol)) {
                    continue;
                }

                double markPrice = node.get("markPrice").asDouble();
                double indexPrice = node.get("indexPrice").asDouble();
                double fundingRate = node.get("lastFundingRate").asDouble();
                long nextFundingTimeMs = node.get("nextFundingTime").asLong();

                // Cache mark price for OI USD calculation
                markPriceCache.put(symbol, markPrice);

                rates.add(new FundingRate(
                        symbol,
                        fundingRate,
                        Instant.now(),
                        Instant.ofEpochMilli(nextFundingTimeMs),
                        markPrice,
                        indexPrice
                ));
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse funding rates response");
        }
        return rates;
    }

    private OpenInterest parseOpenInterest(String body, String symbol) {
        try {
            JsonNode node = objectMapper.readTree(body);
            double oi = node.get("openInterest").asDouble();
            long timeMs = node.get("time").asLong();

            double markPrice = markPriceCache.getOrDefault(symbol, 0.0);
            double oiValueUsd = oi * markPrice;

            return new OpenInterest(symbol, oi, oiValueUsd, Instant.ofEpochMilli(timeMs));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse open interest for %s", symbol);
            return null;
        }
    }

    private LongShortRatio parseLongShortRatio(String body, String symbol) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray() || root.isEmpty()) {
                return null;
            }
            JsonNode node = root.get(0);
            return new LongShortRatio(
                    symbol,
                    node.get("longAccount").asDouble(),
                    node.get("shortAccount").asDouble(),
                    node.get("longShortRatio").asDouble(),
                    Instant.ofEpochMilli(node.get("timestamp").asLong())
            );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse long/short ratio for %s", symbol);
            return null;
        }
    }

    /**
     * Simple rate limiter: max 10 requests/second. Sleeps if budget exhausted.
     */
    private void rateLimit() {
        long nowSecond = System.currentTimeMillis() / 1000;
        if (currentSecond.get() != nowSecond) {
            currentSecond.set(nowSecond);
            requestsThisSecond.set(0);
        }
        if (requestsThisSecond.incrementAndGet() > MAX_REQUESTS_PER_SECOND) {
            try {
                long sleepMs = 1000 - (System.currentTimeMillis() % 1000);
                LOG.debugf("Rate limit: sleeping %dms", sleepMs);
                Thread.sleep(sleepMs);
                currentSecond.set(System.currentTimeMillis() / 1000);
                requestsThisSecond.set(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
