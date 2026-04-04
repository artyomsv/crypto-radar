package com.cryptoradar.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class GeminiAnalysisService {

    private static final Logger LOG = Logger.getLogger(GeminiAnalysisService.class);
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final long CACHE_TTL_MS = 300_000;
    private static final long COOLDOWN_MS = 120_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Optional<String> apiKey;
    private final ConcurrentHashMap<String, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public GeminiAnalysisService(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "gemini.api.key", defaultValue = "") String apiKey) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey.isBlank() ? Optional.empty() : Optional.of(apiKey);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (this.apiKey.isEmpty()) {
            LOG.warn("GEMINI_API_KEY not set — AI analysis disabled");
        } else {
            LOG.info("Gemini AI analysis enabled (gemini-3.1-flash-lite-preview)");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public boolean isEnabled() {
        return apiKey.isPresent();
    }

    public String getCachedAnalysis(String symbol) {
        CachedAnalysis cached = analysisCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            return cached.analysis;
        }
        return null;
    }

    public Instant getCachedAnalysisTimestamp(String symbol) {
        CachedAnalysis cached = analysisCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            return cached.createdAt;
        }
        return null;
    }

    public void triggerAnalysis(String symbol, Map<String, Object> rawData) {
        if (apiKey.isEmpty()) return;
        if (!acquireCooldown(symbol)) return;

        executor.submit(() -> {
            try {
                String analysis = callGemini(symbol, rawData);
                if (analysis != null) {
                    analysisCache.put(symbol, new CachedAnalysis(analysis));
                    LOG.infof("AI analysis complete for %s (%d chars)", symbol, analysis.length());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("AI analysis interrupted for %s", symbol);
            } catch (Exception e) {
                LOG.errorf(e, "Gemini analysis failed for %s", symbol);
            }
        });
    }

    public String analyzeNow(String symbol, Map<String, Object> rawData) {
        if (apiKey.isEmpty()) return "AI analysis unavailable — GEMINI_API_KEY not configured.";

        CachedAnalysis cached = analysisCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            return cached.analysis;
        }

        if (!acquireCooldown(symbol)) {
            return "AI analysis on cooldown. Try again in 2 minutes.";
        }

        try {
            String analysis = callGemini(symbol, rawData);
            if (analysis != null) {
                analysisCache.put(symbol, new CachedAnalysis(analysis));
                return analysis;
            }
            return "AI analysis returned empty response.";
        } catch (Exception e) {
            LOG.errorf(e, "Gemini analysis failed for %s", symbol);
            return "AI analysis temporarily unavailable. Please try again later.";
        }
    }

    private boolean acquireCooldown(String symbol) {
        long now = System.currentTimeMillis();

        if (analysisCache.size() > 50) {
            evictExpiredEntries();
        }

        Long lastRequest = lastRequestTime.get(symbol);
        if (lastRequest != null && now - lastRequest < COOLDOWN_MS) {
            LOG.debugf("Skipping AI analysis for %s — cooldown active", symbol);
            return false;
        }
        lastRequestTime.put(symbol, now);
        return true;
    }

    private void evictExpiredEntries() {
        Iterator<Map.Entry<String, CachedAnalysis>> it = analysisCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) it.remove();
        }
    }

    private String callGemini(String symbol, Map<String, Object> rawData) throws Exception {
        String prompt = buildPrompt(symbol, rawData);

        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);

        String json = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey.get())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.warnf("Gemini API returned %d for %s", response.statusCode(), symbol);
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            return candidates.path(0).path("content").path("parts").path(0).path("text").asText(null);
        }

        return null;
    }

    private String buildPrompt(String symbol, Map<String, Object> rawData) {
        String ticker = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
        String dataJson;
        try {
            dataJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rawData);
        } catch (Exception e) {
            dataJson = rawData.toString();
        }

        return String.format("""
            You are an expert crypto swing trader. Analyze %s/USDT from this data.

            STRICT RULES:
            - Minimum reward:risk ratio is 3:1. Target 4:1 or 5:1.
            - Use ATR(14) for stop sizing: stop = 1.5x ATR from entry.
            - Target must be at least 4.5x ATR from entry (gives ~3:1 minimum).
            - If no setup offers 3:1 R:R, verdict MUST be HOLD.
            - Do NOT suggest trades with tight targets near current price.

            Reply in EXACTLY this format (2 lines only, no markdown, no extras):
            VERDICT: BUY|SELL|HOLD (confidence 1-10) — one sentence why
            TRADE: entry/stop/target R:R=X:1 — one sentence with key level. Or "stay flat — no 3:1 setup available"

            Context: funding rate is per 8h (negative=shorts pay=contrarian bullish), whale pressure is 1h window (0 trades=no data), Fear&Greed 0=extreme fear.

            DATA:
            %s
            """, ticker, dataJson);
    }

    private record CachedAnalysis(String analysis, Instant createdAt) {
        CachedAnalysis(String analysis) {
            this(analysis, Instant.now());
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() - createdAt.toEpochMilli() > CACHE_TTL_MS;
        }
    }
}
