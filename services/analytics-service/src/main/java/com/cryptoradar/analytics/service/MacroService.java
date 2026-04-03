package com.cryptoradar.analytics.service;

import com.cryptoradar.analytics.model.MacroOverview;
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
import java.time.Instant;

@ApplicationScoped
public class MacroService {

    private static final Logger LOG = Logger.getLogger(MacroService.class);

    private static final String COINGECKO_GLOBAL_URL = "https://api.coingecko.com/api/v3/global";
    private static final String COINGECKO_TETHER_URL = "https://api.coingecko.com/api/v3/coins/tether";
    private static final String COINGECKO_USDC_URL = "https://api.coingecko.com/api/v3/coins/usd-coin";
    private static final String DEFILLAMA_TVL_URL = "https://api.llama.fi/v2/historicalChainTvl";

    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    ObjectMapper objectMapper;

    private volatile MacroOverview cachedOverview;
    private volatile long lastFetchTime;

    public MacroOverview getMacroOverview() {
        if (cachedOverview != null && System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS) {
            return cachedOverview;
        }
        return fetchMacroOverview();
    }

    private synchronized MacroOverview fetchMacroOverview() {
        // Double-check after acquiring lock
        if (cachedOverview != null && System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS) {
            return cachedOverview;
        }

        MacroOverview overview = new MacroOverview();
        overview.setTimestamp(Instant.now());

        fetchGlobalData(overview);
        fetchStablecoinData(overview);
        fetchDefiTvl(overview);

        cachedOverview = overview;
        lastFetchTime = System.currentTimeMillis();
        LOG.infof("Macro overview refreshed: BTC %.1f%%, ETH %.1f%%, MCap $%.0fB, TVL $%.0fB",
                overview.getBtcDominance(), overview.getEthDominance(),
                overview.getTotalMarketCapUsd() / 1e9, overview.getDefiTvlUsd() / 1e9);
        return overview;
    }

    private void fetchGlobalData(MacroOverview overview) {
        try {
            String body = httpGet(COINGECKO_GLOBAL_URL);
            if (body == null) return;

            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");

            JsonNode marketCapPct = data.path("market_cap_percentage");
            overview.setBtcDominance(marketCapPct.path("btc").asDouble(0));
            overview.setEthDominance(marketCapPct.path("eth").asDouble(0));

            JsonNode totalMcap = data.path("total_market_cap");
            overview.setTotalMarketCapUsd(totalMcap.path("usd").asDouble(0));
        } catch (Exception e) {
            LOG.warnf("Failed to fetch CoinGecko global data: %s", e.getMessage());
        }
    }

    private void fetchStablecoinData(MacroOverview overview) {
        double usdtCap = fetchCoinMarketCap(COINGECKO_TETHER_URL);
        double usdcCap = fetchCoinMarketCap(COINGECKO_USDC_URL);

        overview.setUsdtMarketCap(usdtCap);
        overview.setUsdcMarketCap(usdcCap);
        overview.setTotalStablecoinCap(usdtCap + usdcCap);
    }

    private double fetchCoinMarketCap(String url) {
        try {
            String body = httpGet(url);
            if (body == null) return 0;

            JsonNode root = objectMapper.readTree(body);
            return root.path("market_data").path("market_cap").path("usd").asDouble(0);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch coin market cap from %s: %s", url, e.getMessage());
            return 0;
        }
    }

    private void fetchDefiTvl(MacroOverview overview) {
        try {
            String body = httpGet(DEFILLAMA_TVL_URL);
            if (body == null) return;

            JsonNode entries = objectMapper.readTree(body);
            if (entries.isArray() && !entries.isEmpty()) {
                // Last entry is the most recent TVL data point
                JsonNode latest = entries.get(entries.size() - 1);
                overview.setDefiTvlUsd(latest.path("tvl").asDouble(0));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to fetch DeFi TVL from DefiLlama: %s", e.getMessage());
        }
    }

    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            LOG.warnf("Non-2xx from %s: %d", url, response.statusCode());
            return null;
        } catch (Exception e) {
            LOG.warnf("HTTP request failed for %s: %s", url, e.getMessage());
            return null;
        }
    }
}
