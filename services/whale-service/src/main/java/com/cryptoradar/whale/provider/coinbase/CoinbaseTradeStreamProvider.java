package com.cryptoradar.whale.provider.coinbase;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.exchange.AbstractExchangeStreamProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class CoinbaseTradeStreamProvider extends AbstractExchangeStreamProvider {

    private static final Map<String, String> SYMBOL_MAP = Map.of(
            "BTC-USD", "BTCUSDT", "ETH-USD", "ETHUSDT", "SOL-USD", "SOLUSDT",
            "XRP-USD", "XRPUSDT", "ADA-USD", "ADAUSDT", "AVAX-USD", "AVAXUSDT",
            "DOT-USD", "DOTUSDT", "LINK-USD", "LINKUSDT", "DOGE-USD", "DOGEUSDT"
    );

    @Override
    public String getExchangeName() {
        return "Coinbase";
    }

    @Override
    protected String getWsUrl() {
        return "wss://ws-feed.exchange.coinbase.com";
    }

    @Override
    protected String getSubscribeMessage() {
        return """
                {"type":"subscribe","channels":[{"name":"matches","product_ids":["BTC-USD","ETH-USD","SOL-USD","XRP-USD","ADA-USD","AVAX-USD","DOT-USD","LINK-USD","DOGE-USD"]}]}""";
    }

    @Override
    protected WhaleTransaction parseTradeMessage(String message) {
        try {
            JsonNode root = mapper().readTree(message);
            String type = root.path("type").asText();
            if (!"match".equals(type) && !"last_match".equals(type)) return null;

            String productId = root.path("product_id").asText();
            String symbol = SYMBOL_MAP.get(productId);
            if (symbol == null) return null;

            double price = root.path("price").asDouble();
            double size = root.path("size").asDouble();
            double value = price * size;
            if (value < getThreshold()) return null;

            String side = "buy".equals(root.path("side").asText()) ? "BUY" : "SELL";
            Instant time = Instant.parse(root.path("time").asText());

            return new WhaleTransaction(time, symbol, price, size, value, side,
                    "coinbase", null, null, null, null, null, null, null);
        } catch (Exception e) {
            return null;
        }
    }
}
