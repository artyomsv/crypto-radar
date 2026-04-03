package com.cryptoradar.marketdata.client;

import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.PriceSnapshot;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class BinanceClient {

    private static final Logger LOG = Logger.getLogger(BinanceClient.class);

    private static final Set<String> TRACKED_SYMBOLS = Set.of(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "ADAUSDT", "AVAXUSDT", "DOTUSDT", "LINKUSDT", "DOGEUSDT"
    );

    private final HttpClient httpClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    BinanceRateLimiter rateLimiter;

    @ConfigProperty(name = "binance.api.base-url")
    String baseUrl;

    @ConfigProperty(name = "binance.api.klines-path")
    String klinesPath;

    @ConfigProperty(name = "binance.api.ticker-path")
    String tickerPath;

    public BinanceClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Fetch kline/candlestick data from Binance.
     * Each kline array: [openTime, open, high, low, close, volume, closeTime, quoteVolume, tradeCount, ...]
     */
    public List<Candle> fetchKlines(String symbol, String interval, int limit) {
        return fetchKlines(symbol, interval, limit, null, null);
    }

    /**
     * Fetch klines with optional time range for backfilling.
     * @param startTime start time in epoch ms (inclusive), null for latest
     * @param endTime end time in epoch ms (inclusive), null for latest
     */
    public List<Candle> fetchKlines(String symbol, String interval, int limit,
                                     Long startTime, Long endTime) {
        rateLimiter.acquire(2); // klines weight = 2
        try {
            StringBuilder url = new StringBuilder(
                    String.format("%s%s?symbol=%s&interval=%s&limit=%d",
                            baseUrl, klinesPath, symbol, interval, limit));
            if (startTime != null) {
                url.append("&startTime=").append(startTime);
            }
            if (endTime != null) {
                url.append("&endTime=").append(endTime);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String usedWeightHeader = response.headers()
                    .firstValue("X-MBX-USED-WEIGHT-1m").orElse(null);
            rateLimiter.recordResponse(response.statusCode(), usedWeightHeader);

            if (response.statusCode() == 429 || response.statusCode() == 418) {
                LOG.warnf("Binance rate limit for %s [%s]: HTTP %d. Will retry after backoff.",
                        symbol, interval, response.statusCode());
                return Collections.emptyList();
            }

            if (response.statusCode() != 200) {
                LOG.errorf("Binance klines API returned %d for %s: %s",
                        response.statusCode(), symbol, response.body());
                return Collections.emptyList();
            }

            return parseKlines(response.body(), symbol, interval);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch klines for %s [%s]", symbol, interval);
            return Collections.emptyList();
        } finally {
            rateLimiter.release();
        }
    }

    private List<Candle> parseKlines(String body, String symbol, String interval) {
        List<Candle> candles = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode kline : root) {
                Instant openTime = Instant.ofEpochMilli(kline.get(0).asLong());
                Double open = kline.get(1).asDouble();
                Double high = kline.get(2).asDouble();
                Double low = kline.get(3).asDouble();
                Double close = kline.get(4).asDouble();
                Double volume = kline.get(5).asDouble();
                Double quoteVolume = kline.get(7).asDouble();
                Integer tradeCount = kline.get(8).asInt();

                candles.add(new Candle(openTime, symbol, interval, open, high, low, close,
                        volume, quoteVolume, tradeCount));
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse klines response for %s [%s]", symbol, interval);
        }
        return candles;
    }

    /**
     * Fetch 24hr ticker for a single symbol.
     */
    public PriceSnapshot fetch24hrTicker(String symbol) {
        try {
            String url = String.format("%s%s?symbol=%s", baseUrl, tickerPath, symbol);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Binance ticker API returned %d for %s: %s",
                        response.statusCode(), symbol, response.body());
                return null;
            }

            JsonNode node = objectMapper.readTree(response.body());
            return parseTicker(node);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch 24hr ticker for %s", symbol);
            return null;
        }
    }

    /**
     * Fetch all 24hr tickers and filter to tracked symbols.
     */
    public List<PriceSnapshot> fetchAll24hrTickers() {
        rateLimiter.acquire(40); // all-tickers weight = 40
        try {
            String url = String.format("%s%s", baseUrl, tickerPath);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String usedWeightHeader = response.headers()
                    .firstValue("X-MBX-USED-WEIGHT-1m").orElse(null);
            rateLimiter.recordResponse(response.statusCode(), usedWeightHeader);

            if (response.statusCode() != 200) {
                LOG.errorf("Binance all-tickers API returned %d: %s",
                        response.statusCode(), response.body());
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<PriceSnapshot> snapshots = new ArrayList<>();

            for (JsonNode node : root) {
                String tickerSymbol = node.get("symbol").asText();
                if (TRACKED_SYMBOLS.contains(tickerSymbol)) {
                    PriceSnapshot snapshot = parseTicker(node);
                    if (snapshot != null) {
                        snapshots.add(snapshot);
                    }
                }
            }

            LOG.infof("Fetched %d tracked tickers from Binance", snapshots.size());
            return snapshots;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch all 24hr tickers");
            return Collections.emptyList();
        } finally {
            rateLimiter.release();
        }
    }

    /**
     * Lightweight price fetch — /api/v3/ticker/price (weight 4 for all symbols).
     * Returns only symbol + price. Used for 1-second real-time updates.
     */
    public Map<String, Double> fetchAllPricesLightweight() {
        rateLimiter.acquire(4); // weight = 4
        try {
            String url = baseUrl + "/api/v3/ticker/price";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String usedWeightHeader = response.headers()
                    .firstValue("X-MBX-USED-WEIGHT-1m").orElse(null);
            rateLimiter.recordResponse(response.statusCode(), usedWeightHeader);

            if (response.statusCode() != 200) {
                return Collections.emptyMap();
            }

            JsonNode root = objectMapper.readTree(response.body());
            Map<String, Double> prices = new HashMap<>();

            for (JsonNode node : root) {
                String sym = node.get("symbol").asText();
                if (TRACKED_SYMBOLS.contains(sym)) {
                    prices.put(sym, node.get("price").asDouble());
                }
            }

            return prices;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch lightweight prices");
            return Collections.emptyMap();
        } finally {
            rateLimiter.release();
        }
    }

    public Set<String> getTrackedSymbols() {
        return TRACKED_SYMBOLS;
    }

    private PriceSnapshot parseTicker(JsonNode node) {
        try {
            String symbol = node.get("symbol").asText();
            Double lastPrice = node.get("lastPrice").asDouble();
            Double priceChange = node.get("priceChange").asDouble();
            Double priceChangePct = node.get("priceChangePercent").asDouble();
            Double volume = node.get("quoteVolume").asDouble();

            return new PriceSnapshot(
                    Instant.now(),
                    symbol,
                    lastPrice,
                    priceChange,
                    priceChangePct,
                    volume,
                    null // Binance doesn't provide market cap in ticker endpoint
            );
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse ticker JSON");
            return null;
        }
    }
}
