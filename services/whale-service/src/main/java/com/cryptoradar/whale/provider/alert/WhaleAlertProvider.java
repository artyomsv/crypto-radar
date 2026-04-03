package com.cryptoradar.whale.provider.alert;

import com.cryptoradar.whale.model.WhaleTransaction;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class WhaleAlertProvider {

    private static final Logger LOG = Logger.getLogger(WhaleAlertProvider.class);

    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final long RATE_WINDOW_MS = 60_000;
    private static final int LOOKBACK_SECONDS = 60;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final Map<String, String> SYMBOL_MAP = Map.ofEntries(
            Map.entry("btc", "BTCUSDT"),
            Map.entry("eth", "ETHUSDT"),
            Map.entry("sol", "SOLUSDT"),
            Map.entry("xrp", "XRPUSDT"),
            Map.entry("ada", "ADAUSDT"),
            Map.entry("doge", "DOGEUSDT"),
            Map.entry("bnb", "BNBUSDT"),
            Map.entry("dot", "DOTUSDT"),
            Map.entry("link", "LINKUSDT"),
            Map.entry("avax", "AVAXUSDT")
    );

    @ConfigProperty(name = "whale.alert.api.key")
    Optional<String> apiKey;

    @ConfigProperty(name = "whale.alert.api.url")
    String baseUrl;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    public boolean isEnabled() {
        return apiKey.isPresent() && !apiKey.get().isBlank();
    }

    public List<WhaleTransaction> fetchRecentTransactions() {
        if (!isEnabled()) {
            LOG.debug("Whale Alert provider disabled — no API key configured");
            return List.of();
        }

        if (!acquireRateLimit()) {
            LOG.debug("Whale Alert rate limit reached, skipping request");
            return List.of();
        }

        try {
            long start = Instant.now().minusSeconds(LOOKBACK_SECONDS).getEpochSecond();
            String url = String.format("%s/transactions?api_key=%s&min_value=50000&start=%d",
                    baseUrl, apiKey.orElse(""), start);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warnf("Whale Alert API returned status %d: %s", response.statusCode(), response.body());
                return List.of();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch Whale Alert transactions");
            return List.of();
        }
    }

    private List<WhaleTransaction> parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);

            String result = root.has("result") ? root.get("result").asText() : "";
            if (!"success".equals(result)) {
                LOG.warnf("Whale Alert API returned result: %s", result);
                return List.of();
            }

            JsonNode transactions = root.get("transactions");
            if (transactions == null || !transactions.isArray()) {
                return List.of();
            }

            List<WhaleTransaction> mapped = new ArrayList<>();
            for (JsonNode tx : transactions) {
                WhaleTransaction whale = mapTransaction(tx);
                if (whale != null) {
                    mapped.add(whale);
                }
            }

            LOG.infof("Whale Alert: fetched %d transactions, mapped %d to tracked symbols",
                    transactions.size(), mapped.size());
            return mapped;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse Whale Alert response");
            return List.of();
        }
    }

    private WhaleTransaction mapTransaction(JsonNode tx) {
        String symbol = tx.has("symbol") ? tx.get("symbol").asText().toLowerCase() : "";
        String mappedSymbol = SYMBOL_MAP.get(symbol);
        if (mappedSymbol == null) {
            return null;
        }

        double amount = tx.has("amount") ? tx.get("amount").asDouble() : 0;
        double amountUsd = tx.has("amount_usd") ? tx.get("amount_usd").asDouble() : 0;
        double price = amount > 0 ? amountUsd / amount : 0;
        long timestamp = tx.has("timestamp") ? tx.get("timestamp").asLong() : 0;
        String blockchain = tx.has("blockchain") ? tx.get("blockchain").asText() : "";
        String txHash = tx.has("hash") ? tx.get("hash").asText() : "";
        String txId = tx.has("id") ? tx.get("id").asText() : "";

        String fromAddress = "";
        String fromOwner = "";
        String fromOwnerType = "";
        if (tx.has("from")) {
            JsonNode from = tx.get("from");
            fromAddress = from.has("address") ? from.get("address").asText() : "";
            fromOwner = from.has("owner") ? from.get("owner").asText() : "";
            fromOwnerType = from.has("owner_type") ? from.get("owner_type").asText() : "";
        }

        String toAddress = "";
        String toOwner = "";
        String toOwnerType = "";
        if (tx.has("to")) {
            JsonNode to = tx.get("to");
            toAddress = to.has("address") ? to.get("address").asText() : "";
            toOwner = to.has("owner") ? to.get("owner").asText() : "";
            toOwnerType = to.has("owner_type") ? to.get("owner_type").asText() : "";
        }

        String side = determineSide(fromOwnerType, toOwnerType);

        return new WhaleTransaction(
                Instant.ofEpochSecond(timestamp),
                mappedSymbol,
                price,
                amount,
                amountUsd,
                side,
                "whale-alert",
                txId,
                fromAddress,
                toAddress,
                fromOwner,
                toOwner,
                blockchain,
                txHash
        );
    }

    /**
     * Determine trade side based on address ownership.
     * Exchange withdrawal (from=exchange, to=unknown) = BUY (someone bought and withdrew).
     * Exchange deposit (from=unknown, to=exchange) = SELL (someone deposited to sell).
     * All other transfers default to BUY.
     */
    private String determineSide(String fromOwnerType, String toOwnerType) {
        if ("exchange".equals(fromOwnerType) && !"exchange".equals(toOwnerType)) {
            return "BUY";
        }
        if (!"exchange".equals(fromOwnerType) && "exchange".equals(toOwnerType)) {
            return "SELL";
        }
        return "BUY";
    }

    private boolean acquireRateLimit() {
        long now = System.currentTimeMillis();
        long windowStartMs = windowStart.get();

        if (now - windowStartMs >= RATE_WINDOW_MS) {
            // Reset window — use CAS to avoid race conditions
            if (windowStart.compareAndSet(windowStartMs, now)) {
                requestCount.set(1);
                return true;
            }
        }

        int current = requestCount.incrementAndGet();
        if (current > MAX_REQUESTS_PER_MINUTE) {
            requestCount.decrementAndGet();
            return false;
        }
        return true;
    }
}
