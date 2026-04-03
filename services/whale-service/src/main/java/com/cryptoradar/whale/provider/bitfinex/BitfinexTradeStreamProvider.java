package com.cryptoradar.whale.provider.bitfinex;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.exchange.AbstractExchangeStreamProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BitfinexTradeStreamProvider extends AbstractExchangeStreamProvider {

    private static final List<String> BFX_SYMBOLS = List.of(
            "tBTCUSD", "tETHUSD", "tSOLUSD", "tXRPUSD", "tADAUSD",
            "tDOGEUSD", "tLINKUSD", "tDOTUSD", "tAVAXUSD"
    );

    private static final Map<String, String> SYMBOL_MAP = Map.of(
            "tBTCUSD", "BTCUSDT", "tETHUSD", "ETHUSDT", "tSOLUSD", "SOLUSDT",
            "tXRPUSD", "XRPUSDT", "tADAUSD", "ADAUSDT", "tDOGEUSD", "DOGEUSDT",
            "tLINKUSD", "LINKUSDT", "tDOTUSD", "DOTUSDT", "tAVAXUSD", "AVAXUSDT"
    );

    /** Bitfinex uses numeric channel IDs — mapped after subscription confirmation */
    private final ConcurrentHashMap<Integer, String> channelSymbolMap = new ConcurrentHashMap<>();

    @Override
    public String getExchangeName() {
        return "Bitfinex";
    }

    @Override
    protected String getWsUrl() {
        return "wss://api-pub.bitfinex.com/ws/2";
    }

    @Override
    protected String getSubscribeMessage() {
        // First symbol subscription — the rest are sent via onConnected
        return "{\"event\":\"subscribe\",\"channel\":\"trades\",\"symbol\":\"" + BFX_SYMBOLS.getFirst() + "\"}";
    }

    @Override
    protected void onConnected(WebSocket ws) {
        // Send remaining symbol subscriptions (first was already sent by base class)
        for (int i = 1; i < BFX_SYMBOLS.size(); i++) {
            String msg = "{\"event\":\"subscribe\",\"channel\":\"trades\",\"symbol\":\"" + BFX_SYMBOLS.get(i) + "\"}";
            ws.sendText(msg, true);
        }
    }

    @Override
    public void connect() {
        channelSymbolMap.clear();
        super.connect();
    }

    @Override
    protected WhaleTransaction parseTradeMessage(String message) {
        try {
            JsonNode root = mapper().readTree(message);

            // Handle subscription confirmation: {"event":"subscribed","channel":"trades","chanId":123,"symbol":"tBTCUSD"}
            if (root.isObject() && "subscribed".equals(root.path("event").asText())) {
                int chanId = root.path("chanId").asInt();
                String sym = root.path("symbol").asText();
                channelSymbolMap.put(chanId, sym);
                return null;
            }

            // Trade updates: [CHANNEL_ID, "te"|"tu", [ID, TIMESTAMP_MS, AMOUNT, PRICE]]
            // Heartbeat: [CHANNEL_ID, "hb"]
            if (!root.isArray() || root.size() < 3) return null;

            int chanId = root.get(0).asInt();
            String msgType = root.get(1).isTextual() ? root.get(1).asText() : "";

            if ("hb".equals(msgType)) return null;
            // "te" = trade executed, "tu" = trade update — use "te" to avoid duplicates
            if (!"te".equals(msgType)) return null;

            String bfxSymbol = channelSymbolMap.get(chanId);
            if (bfxSymbol == null) return null;
            String symbol = SYMBOL_MAP.get(bfxSymbol);
            if (symbol == null) return null;

            JsonNode trade = root.get(2);
            if (!trade.isArray() || trade.size() < 4) return null;

            long tsMs = trade.get(1).asLong();
            double amount = trade.get(2).asDouble(); // positive = buy, negative = sell
            double price = trade.get(3).asDouble();
            double qty = Math.abs(amount);
            double value = price * qty;
            if (value < getThreshold()) return null;

            String side = amount > 0 ? "BUY" : "SELL";
            Instant time = Instant.ofEpochMilli(tsMs);

            return new WhaleTransaction(time, symbol, price, qty, value, side,
                    "bitfinex", null, null, null, null, null, null, null);
        } catch (Exception e) {
            return null;
        }
    }
}
