package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.model.OrderBookDepth;
import com.cryptoradar.marketdata.model.PriceLevel;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches order book depth from Binance, OKX, Bybit, and Coinbase in parallel,
 * normalizes price levels, and merges into a single aggregated depth view.
 */
@ApplicationScoped
public class OrderBookService {

    private static final Logger LOG = Logger.getLogger(OrderBookService.class);

    private static final int DEPTH_LIMIT = 50;
    private static final int MAX_LEVELS = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    @Inject
    ObjectMapper objectMapper;

    public OrderBookService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public OrderBookDepth getAggregatedDepth(String symbol) {
        String binanceSymbol = symbol.toUpperCase();
        String okxSymbol = convertToOkxSymbol(binanceSymbol);
        String coinbaseSymbol = convertToCoinbaseSymbol(binanceSymbol);
        String bybitSymbol = binanceSymbol;

        CompletableFuture<ExchangeBook> binanceFuture = CompletableFuture.supplyAsync(
                () -> fetchBinance(binanceSymbol));
        CompletableFuture<ExchangeBook> okxFuture = CompletableFuture.supplyAsync(
                () -> fetchOkx(okxSymbol));
        CompletableFuture<ExchangeBook> bybitFuture = CompletableFuture.supplyAsync(
                () -> fetchBybit(bybitSymbol));
        CompletableFuture<ExchangeBook> coinbaseFuture = CompletableFuture.supplyAsync(
                () -> fetchCoinbase(coinbaseSymbol));

        CompletableFuture.allOf(binanceFuture, okxFuture, bybitFuture, coinbaseFuture).join();

        List<ExchangeBook> books = new ArrayList<>();
        addIfPresent(books, binanceFuture, "Binance");
        addIfPresent(books, okxFuture, "OKX");
        addIfPresent(books, bybitFuture, "Bybit");
        addIfPresent(books, coinbaseFuture, "Coinbase");

        return mergeBooks(symbol, books);
    }

    // --- Exchange fetch methods ---

    private ExchangeBook fetchBinance(String symbol) {
        try {
            String url = "https://api.binance.com/api/v3/depth?symbol=" + symbol + "&limit=" + DEPTH_LIMIT;
            String body = fetchUrl(url);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            List<double[]> bids = parseBinanceLevels(root.get("bids"));
            List<double[]> asks = parseBinanceLevels(root.get("asks"));
            return new ExchangeBook(bids, asks);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch Binance depth for %s: %s", symbol, e.getMessage());
            return null;
        }
    }

    private ExchangeBook fetchOkx(String symbol) {
        try {
            String url = "https://www.okx.com/api/v5/market/books?instId=" + symbol + "&sz=" + DEPTH_LIMIT;
            String body = fetchUrl(url);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) return null;

            JsonNode book = data.get(0);
            List<double[]> bids = parseOkxLevels(book.get("bids"));
            List<double[]> asks = parseOkxLevels(book.get("asks"));
            return new ExchangeBook(bids, asks);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch OKX depth for %s: %s", symbol, e.getMessage());
            return null;
        }
    }

    private ExchangeBook fetchBybit(String symbol) {
        try {
            String url = "https://api.bybit.com/v5/market/orderbook?category=spot&symbol=" + symbol + "&limit=" + DEPTH_LIMIT;
            String body = fetchUrl(url);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root.get("result");
            if (result == null) return null;

            List<double[]> bids = parseBinanceLevels(result.get("b"));
            List<double[]> asks = parseBinanceLevels(result.get("a"));
            return new ExchangeBook(bids, asks);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch Bybit depth for %s: %s", symbol, e.getMessage());
            return null;
        }
    }

    private ExchangeBook fetchCoinbase(String symbol) {
        try {
            String url = "https://api.exchange.coinbase.com/products/" + symbol + "/book?level=2";
            String body = fetchUrl(url);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            List<double[]> bids = parseCoinbaseLevels(root.get("bids"));
            List<double[]> asks = parseCoinbaseLevels(root.get("asks"));
            return new ExchangeBook(bids, asks);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch Coinbase depth for %s: %s", symbol, e.getMessage());
            return null;
        }
    }

    // --- Parsing helpers ---

    private List<double[]> parseBinanceLevels(JsonNode levels) {
        List<double[]> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) return result;
        for (JsonNode level : levels) {
            double price = level.get(0).asDouble();
            double quantity = level.get(1).asDouble();
            result.add(new double[]{price, quantity});
        }
        return result;
    }

    private List<double[]> parseOkxLevels(JsonNode levels) {
        List<double[]> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) return result;
        for (JsonNode level : levels) {
            double price = level.get(0).asDouble();
            double quantity = level.get(1).asDouble();
            result.add(new double[]{price, quantity});
        }
        return result;
    }

    private List<double[]> parseCoinbaseLevels(JsonNode levels) {
        List<double[]> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) return result;
        for (JsonNode level : levels) {
            double price = level.get(0).asDouble();
            double quantity = level.get(1).asDouble();
            result.add(new double[]{price, quantity});
        }
        return result;
    }

    // --- Merging ---

    private OrderBookDepth mergeBooks(String symbol, List<ExchangeBook> books) {
        Map<Long, MergedLevel> bidMap = new HashMap<>();
        Map<Long, MergedLevel> askMap = new HashMap<>();

        // Determine price precision based on the best bid from first available book
        double refPrice = findReferencePrice(books);
        double tickSize = calculateTickSize(refPrice);

        for (ExchangeBook book : books) {
            mergeLevels(book.bids, bidMap, tickSize);
            mergeLevels(book.asks, askMap, tickSize);
        }

        List<PriceLevel> bids = toSortedLevels(bidMap, Comparator.comparingDouble(PriceLevel::getPrice).reversed(), MAX_LEVELS);
        List<PriceLevel> asks = toSortedLevels(askMap, Comparator.comparingDouble(PriceLevel::getPrice), MAX_LEVELS);

        OrderBookDepth depth = new OrderBookDepth();
        depth.setSymbol(symbol);
        depth.setTimestamp(Instant.now());
        depth.setBids(bids);
        depth.setAsks(asks);

        double bestBid = bids.isEmpty() ? 0 : bids.getFirst().getPrice();
        double bestAsk = asks.isEmpty() ? 0 : asks.getFirst().getPrice();
        depth.setBestBid(bestBid);
        depth.setBestAsk(bestAsk);

        double spread = bestAsk - bestBid;
        depth.setSpread(spread);
        depth.setSpreadPct(bestBid > 0 ? (spread / bestBid) * 100 : 0);

        double totalBidVol = bids.stream().mapToDouble(PriceLevel::getTotalUsd).sum();
        double totalAskVol = asks.stream().mapToDouble(PriceLevel::getTotalUsd).sum();
        depth.setTotalBidVolume(totalBidVol);
        depth.setTotalAskVolume(totalAskVol);

        double totalVol = totalBidVol + totalAskVol;
        depth.setBidAskImbalance(totalVol > 0 ? ((totalBidVol - totalAskVol) / totalVol) * 100 : 0);

        return depth;
    }

    private void mergeLevels(List<double[]> levels, Map<Long, MergedLevel> map, double tickSize) {
        for (double[] level : levels) {
            double price = level[0];
            double quantity = level[1];
            long bucket = Math.round(price / tickSize);

            map.compute(bucket, (key, existing) -> {
                if (existing == null) {
                    return new MergedLevel(bucket * tickSize, quantity, 1);
                }
                existing.quantity += quantity;
                existing.exchangeCount++;
                return existing;
            });
        }
    }

    private List<PriceLevel> toSortedLevels(Map<Long, MergedLevel> map, Comparator<PriceLevel> comparator, int limit) {
        return map.values().stream()
                .map(ml -> new PriceLevel(ml.price, ml.quantity, ml.price * ml.quantity, ml.exchangeCount))
                .sorted(comparator)
                .limit(limit)
                .toList();
    }

    private double findReferencePrice(List<ExchangeBook> books) {
        for (ExchangeBook book : books) {
            if (!book.bids.isEmpty()) {
                return book.bids.getFirst()[0];
            }
        }
        return 100;
    }

    /**
     * Determine tick size for grouping price levels.
     * Higher prices use larger ticks to avoid excessive granularity.
     */
    private double calculateTickSize(double price) {
        if (price >= 10000) return 10.0;
        if (price >= 1000) return 1.0;
        if (price >= 100) return 0.1;
        if (price >= 10) return 0.01;
        if (price >= 1) return 0.001;
        return 0.0001;
    }

    // --- Symbol conversion ---

    private String convertToOkxSymbol(String binanceSymbol) {
        // BTCUSDT -> BTC-USDT
        if (binanceSymbol.endsWith("USDT")) {
            return binanceSymbol.substring(0, binanceSymbol.length() - 4) + "-USDT";
        }
        return binanceSymbol;
    }

    private String convertToCoinbaseSymbol(String binanceSymbol) {
        // BTCUSDT -> BTC-USD (Coinbase uses USD, not USDT)
        if (binanceSymbol.endsWith("USDT")) {
            return binanceSymbol.substring(0, binanceSymbol.length() - 4) + "-USD";
        }
        return binanceSymbol;
    }

    // --- HTTP helper ---

    private String fetchUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.debugf("HTTP %d from %s", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            LOG.debugf("Failed to fetch %s: %s", url, e.getMessage());
            return null;
        }
    }

    private void addIfPresent(List<ExchangeBook> books, CompletableFuture<ExchangeBook> future, String exchange) {
        try {
            ExchangeBook book = future.get();
            if (book != null) {
                books.add(book);
            } else {
                LOG.debugf("%s order book unavailable", exchange);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to get %s order book: %s", exchange, e.getMessage());
        }
    }

    // --- Internal structures ---

    private record ExchangeBook(List<double[]> bids, List<double[]> asks) {
    }

    private static class MergedLevel {
        double price;
        double quantity;
        int exchangeCount;

        MergedLevel(double price, double quantity, int exchangeCount) {
            this.price = price;
            this.quantity = quantity;
            this.exchangeCount = exchangeCount;
        }
    }
}
