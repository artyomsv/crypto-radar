package com.cryptoradar.gateway.service;

import com.cryptoradar.gateway.client.ServiceClient;
import com.cryptoradar.gateway.dto.CryptoDetail;
import com.cryptoradar.gateway.dto.DashboardData;
import com.cryptoradar.gateway.dto.PriceData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class AggregationService {

    private static final Logger LOG = Logger.getLogger(AggregationService.class);

    private static final Map<String, String> SYMBOL_NAMES = Map.ofEntries(
            Map.entry("BTCUSDT", "Bitcoin"),
            Map.entry("ETHUSDT", "Ethereum"),
            Map.entry("XRPUSDT", "XRP"),
            Map.entry("BNBUSDT", "BNB"),
            Map.entry("SOLUSDT", "Solana"),
            Map.entry("TRXUSDT", "TRON"),
            Map.entry("DOGEUSDT", "Dogecoin"),
            Map.entry("BCHUSDT", "Bitcoin Cash"),
            Map.entry("ADAUSDT", "Cardano"),
            Map.entry("LINKUSDT", "Chainlink"),
            Map.entry("XMRUSDT", "Monero"),
            Map.entry("XLMUSDT", "Stellar"),
            Map.entry("LTCUSDT", "Litecoin"),
            Map.entry("ZECUSDT", "Zcash")
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    ServiceClient serviceClient;

    public DashboardData getDashboard() {
        // Parallel fetch: prices, market overview, latest news
        CompletableFuture<JsonNode> pricesFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getPrices(), executor);
        CompletableFuture<JsonNode> marketOverviewFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getMarketOverview(), executor);
        CompletableFuture<JsonNode> newsFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getLatestNews(10), executor);

        // Wait for all to complete
        CompletableFuture.allOf(pricesFuture, marketOverviewFuture, newsFuture).join();

        JsonNode pricesNode = pricesFuture.join();
        JsonNode marketOverviewNode = marketOverviewFuture.join();
        JsonNode newsNode = newsFuture.join();

        // Parse prices and enrich with sparkline data
        List<PriceData> prices = parsePrices(pricesNode);

        // Fetch sparklines in parallel for each price
        List<CompletableFuture<Void>> sparklineFutures = new ArrayList<>();
        for (PriceData priceData : prices) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    JsonNode candles = serviceClient.getCandles(priceData.getSymbol(), "1h", 24);
                    priceData.setSparkline(extractCloses(candles));
                } catch (Exception e) {
                    LOG.debugf("Failed to fetch sparkline for %s: %s", priceData.getSymbol(), e.getMessage());
                    priceData.setSparkline(Collections.emptyList());
                }
            }, executor);
            sparklineFutures.add(future);
        }
        CompletableFuture.allOf(sparklineFutures.toArray(new CompletableFuture[0])).join();

        // Build dashboard
        DashboardData dashboard = new DashboardData();
        dashboard.setPrices(prices);
        dashboard.setMarketOverview(nodeToObject(marketOverviewNode));
        dashboard.setLatestNews(nodeToList(newsNode));
        dashboard.setTimestamp(Instant.now());

        return dashboard;
    }

    public CryptoDetail getCryptoDetail(String symbol) {
        // News service uses base symbol (BTC) not trading pair (BTCUSDT)
        String baseSymbol = symbol.replace("USDT", "");

        // Parallel fetch all detail data
        CompletableFuture<JsonNode> pricesFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getPrices(), executor);
        CompletableFuture<JsonNode> candlesFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getCandles(symbol, "1h", 100), executor);
        CompletableFuture<JsonNode> analysisFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getAnalysis(symbol), executor);
        CompletableFuture<JsonNode> newsFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getNewsBySymbol(baseSymbol, 20), executor);
        CompletableFuture<JsonNode> sentimentFuture = CompletableFuture.supplyAsync(
                () -> serviceClient.getSentimentHistory(baseSymbol, 30), executor);

        CompletableFuture.allOf(pricesFuture, candlesFuture, analysisFuture, newsFuture, sentimentFuture).join();

        JsonNode pricesNode = pricesFuture.join();
        JsonNode candlesNode = candlesFuture.join();
        JsonNode analysisNode = analysisFuture.join();
        JsonNode newsNode = newsFuture.join();
        JsonNode sentimentNode = sentimentFuture.join();

        // Find the specific symbol's price data
        PriceData priceData = findPriceForSymbol(pricesNode, symbol);

        // Extract technical indicators from analysis response
        Object technicalIndicators = null;
        Object analysis = null;
        if (analysisNode != null) {
            technicalIndicators = nodeToObject(analysisNode.get("technicalIndicators"));
            analysis = nodeToObject(analysisNode);
        }

        CryptoDetail detail = new CryptoDetail();
        detail.setPriceData(priceData);
        detail.setCandles(nodeToList(candlesNode));
        detail.setTechnicalIndicators(technicalIndicators);
        detail.setAnalysis(analysis);
        detail.setNews(nodeToList(newsNode));
        detail.setSentimentHistory(nodeToList(sentimentNode));

        return detail;
    }

    private List<PriceData> parsePrices(JsonNode pricesNode) {
        List<PriceData> prices = new ArrayList<>();
        if (pricesNode == null) {
            return prices;
        }

        if (pricesNode.isArray()) {
            for (JsonNode node : pricesNode) {
                PriceData pd = mapToPriceData(node);
                if (pd != null) {
                    prices.add(pd);
                }
            }
        } else if (pricesNode.isObject()) {
            // Could be a map of symbol -> price data
            pricesNode.fields().forEachRemaining(entry -> {
                PriceData pd = mapToPriceData(entry.getValue());
                if (pd != null) {
                    if (pd.getSymbol() == null) {
                        pd.setSymbol(entry.getKey());
                    }
                    prices.add(pd);
                }
            });
        }

        return prices;
    }

    private PriceData mapToPriceData(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            PriceData pd = new PriceData();
            pd.setSymbol(textOrNull(node, "symbol"));
            pd.setName(SYMBOL_NAMES.getOrDefault(pd.getSymbol(), pd.getSymbol()));
            pd.setPrice(doubleOrNull(node, "price"));
            pd.setPriceChange24h(doubleOrNull(node, "priceChange24h"));
            pd.setPriceChangePct24h(doubleOrNull(node, "priceChangePct24h"));
            pd.setVolume24h(doubleOrNull(node, "volume24h"));
            pd.setMarketCap(doubleOrNull(node, "marketCap"));
            return pd;
        } catch (Exception e) {
            LOG.debugf("Failed to parse price data: %s", e.getMessage());
            return null;
        }
    }

    private PriceData findPriceForSymbol(JsonNode pricesNode, String symbol) {
        List<PriceData> all = parsePrices(pricesNode);
        return all.stream()
                .filter(p -> symbol.equalsIgnoreCase(p.getSymbol()))
                .findFirst()
                .orElseGet(() -> {
                    PriceData pd = new PriceData();
                    pd.setSymbol(symbol);
                    pd.setName(SYMBOL_NAMES.getOrDefault(symbol, symbol));
                    return pd;
                });
    }

    private List<Double> extractCloses(JsonNode candlesNode) {
        List<Double> closes = new ArrayList<>();
        if (candlesNode == null) {
            return closes;
        }
        if (candlesNode.isArray()) {
            for (JsonNode candle : candlesNode) {
                JsonNode closeNode = candle.get("close");
                if (closeNode != null && closeNode.isNumber()) {
                    closes.add(closeNode.asDouble());
                }
            }
        }
        return closes;
    }

    private Object nodeToObject(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object> nodeToList(JsonNode node) {
        if (node == null) {
            return Collections.emptyList();
        }
        try {
            if (node.isArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonNode item : node) {
                    list.add(objectMapper.treeToValue(item, Object.class));
                }
                return list;
            }
            return List.of(objectMapper.treeToValue(node, Object.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private Double doubleOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asDouble() : null;
    }
}
