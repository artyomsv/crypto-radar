package com.cryptoradar.whale.provider.bybit;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.exchange.AbstractExchangeStreamProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

@ApplicationScoped
public class BybitTradeStreamProvider extends AbstractExchangeStreamProvider {

    private static final Logger LOG = Logger.getLogger(BybitTradeStreamProvider.class);

    @Override
    public String getExchangeName() {
        return "Bybit";
    }

    @Override
    protected String getWsUrl() {
        return "wss://stream.bybit.com/v5/public/spot";
    }

    @Override
    protected String getPingMessage() {
        return "{\"op\":\"ping\"}";
    }

    @Override
    protected String getSubscribeMessage() {
        return """
                {"op":"subscribe","args":["publicTrade.BTCUSDT","publicTrade.ETHUSDT","publicTrade.XRPUSDT","publicTrade.BNBUSDT","publicTrade.SOLUSDT","publicTrade.TRXUSDT","publicTrade.DOGEUSDT","publicTrade.BCHUSDT","publicTrade.ADAUSDT","publicTrade.LINKUSDT","publicTrade.XMRUSDT","publicTrade.XLMUSDT","publicTrade.LTCUSDT","publicTrade.ZECUSDT"]}""";
    }

    @Override
    protected WhaleTransaction parseTradeMessage(String message) {
        try {
            JsonNode root = mapper().readTree(message);
            String topic = root.path("topic").asText();
            if (!topic.startsWith("publicTrade.")) return null;

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return null;

            for (JsonNode trade : data) {
                String symbol = trade.path("s").asText();
                double price = Double.parseDouble(trade.path("p").asText());
                double qty = Double.parseDouble(trade.path("v").asText());
                double value = price * qty;
                if (value < getThresholdForSymbol(symbol)) continue;

                String side = "Buy".equals(trade.path("S").asText()) ? "BUY" : "SELL";
                long tsMs = trade.path("T").asLong();
                Instant time = Instant.ofEpochMilli(tsMs);

                return new WhaleTransaction(time, symbol, price, qty, value, side,
                        "bybit", null, null, null, null, null, null, null);
            }
            return null;
        } catch (Exception e) {
            LOG.debugf("[Bybit] Failed to parse trade message: %s", e.getMessage());
            return null;
        }
    }
}
