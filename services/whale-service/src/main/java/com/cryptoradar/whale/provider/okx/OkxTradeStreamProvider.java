package com.cryptoradar.whale.provider.okx;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.exchange.AbstractExchangeStreamProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class OkxTradeStreamProvider extends AbstractExchangeStreamProvider {

    private static final Map<String, String> SYMBOL_MAP = Map.ofEntries(
            Map.entry("BTC-USDT", "BTCUSDT"), Map.entry("ETH-USDT", "ETHUSDT"),
            Map.entry("XRP-USDT", "XRPUSDT"), Map.entry("BNB-USDT", "BNBUSDT"),
            Map.entry("SOL-USDT", "SOLUSDT"), Map.entry("TRX-USDT", "TRXUSDT"),
            Map.entry("DOGE-USDT", "DOGEUSDT"), Map.entry("BCH-USDT", "BCHUSDT"),
            Map.entry("ADA-USDT", "ADAUSDT"), Map.entry("LINK-USDT", "LINKUSDT"),
            Map.entry("XMR-USDT", "XMRUSDT"), Map.entry("XLM-USDT", "XLMUSDT"),
            Map.entry("LTC-USDT", "LTCUSDT"), Map.entry("ZEC-USDT", "ZECUSDT")
    );

    @Override
    public String getExchangeName() {
        return "OKX";
    }

    @Override
    protected String getWsUrl() {
        return "wss://ws.okx.com:8443/ws/v5/public";
    }

    @Override
    protected String getSubscribeMessage() {
        return """
                {"op":"subscribe","args":[{"channel":"trades","instId":"BTC-USDT"},{"channel":"trades","instId":"ETH-USDT"},{"channel":"trades","instId":"XRP-USDT"},{"channel":"trades","instId":"BNB-USDT"},{"channel":"trades","instId":"SOL-USDT"},{"channel":"trades","instId":"TRX-USDT"},{"channel":"trades","instId":"DOGE-USDT"},{"channel":"trades","instId":"BCH-USDT"},{"channel":"trades","instId":"ADA-USDT"},{"channel":"trades","instId":"LINK-USDT"},{"channel":"trades","instId":"XMR-USDT"},{"channel":"trades","instId":"XLM-USDT"},{"channel":"trades","instId":"LTC-USDT"},{"channel":"trades","instId":"ZEC-USDT"}]}""";
    }

    @Override
    protected WhaleTransaction parseTradeMessage(String message) {
        try {
            JsonNode root = mapper().readTree(message);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return null;

            for (JsonNode trade : data) {
                String instId = trade.path("instId").asText();
                String symbol = SYMBOL_MAP.get(instId);
                if (symbol == null) continue;

                double price = Double.parseDouble(trade.path("px").asText());
                double qty = Double.parseDouble(trade.path("sz").asText());
                double value = price * qty;
                if (value < getThreshold()) continue;

                String side = "buy".equals(trade.path("side").asText()) ? "BUY" : "SELL";
                long tsMs = Long.parseLong(trade.path("ts").asText());
                Instant time = Instant.ofEpochMilli(tsMs);

                return new WhaleTransaction(time, symbol, price, qty, value, side,
                        "okx", null, null, null, null, null, null, null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
