package com.cryptoradar.whale.provider.kraken;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.exchange.AbstractExchangeStreamProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class KrakenTradeStreamProvider extends AbstractExchangeStreamProvider {

    private static final Map<String, String> SYMBOL_MAP = Map.of(
            "BTC/USD", "BTCUSDT", "ETH/USD", "ETHUSDT", "SOL/USD", "SOLUSDT",
            "XRP/USD", "XRPUSDT", "ADA/USD", "ADAUSDT", "AVAX/USD", "AVAXUSDT",
            "DOT/USD", "DOTUSDT", "LINK/USD", "LINKUSDT", "DOGE/USD", "DOGEUSDT"
    );

    @Override
    public String getExchangeName() {
        return "Kraken";
    }

    @Override
    protected String getWsUrl() {
        return "wss://ws.kraken.com/v2";
    }

    @Override
    protected String getSubscribeMessage() {
        return """
                {"method":"subscribe","params":{"channel":"trade","symbol":["BTC/USD","ETH/USD","SOL/USD","XRP/USD","ADA/USD","AVAX/USD","DOT/USD","LINK/USD","DOGE/USD"]}}""";
    }

    @Override
    protected WhaleTransaction parseTradeMessage(String message) {
        try {
            JsonNode root = mapper().readTree(message);
            if (!"trade".equals(root.path("channel").asText())) return null;
            if (!"update".equals(root.path("type").asText())) return null;

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return null;

            for (JsonNode trade : data) {
                String pair = trade.path("symbol").asText();
                String symbol = SYMBOL_MAP.get(pair);
                if (symbol == null) continue;

                double price = trade.path("price").asDouble();
                double qty = trade.path("qty").asDouble();
                double value = price * qty;
                if (value < getThreshold()) continue;

                String side = "buy".equals(trade.path("side").asText()) ? "BUY" : "SELL";
                Instant time = Instant.parse(trade.path("timestamp").asText());

                return new WhaleTransaction(time, symbol, price, qty, value, side,
                        "kraken", null, null, null, null, null, null, null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
