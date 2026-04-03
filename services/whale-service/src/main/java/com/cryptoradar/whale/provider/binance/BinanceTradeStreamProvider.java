package com.cryptoradar.whale.provider.binance;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.exchange.AbstractExchangeStreamProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class BinanceTradeStreamProvider extends AbstractExchangeStreamProvider {

    private static final Logger LOG = Logger.getLogger(BinanceTradeStreamProvider.class);

    private static final List<String> SYMBOLS = List.of(
            "btcusdt", "ethusdt", "xrpusdt", "bnbusdt", "solusdt",
            "trxusdt", "dogeusdt", "bchusdt", "adausdt", "linkusdt",
            "xmrusdt", "xlmusdt", "ltcusdt", "zecusdt"
    );

    @ConfigProperty(name = "whale.binance.ws.url")
    String baseWsUrl;

    @Override
    public String getExchangeName() {
        return "Binance";
    }

    @Override
    protected String getWsUrl() {
        String streams = SYMBOLS.stream()
                .map(s -> s + "@aggTrade")
                .collect(Collectors.joining("/"));
        return baseWsUrl + "?streams=" + streams;
    }

    @Override
    protected String getSubscribeMessage() {
        return null; // Binance combined stream doesn't require a subscribe message
    }

    @Override
    protected WhaleTransaction parseTradeMessage(String message) {
        try {
            JsonNode root = mapper().readTree(message);
            JsonNode data = root.get("data");
            if (data == null) return null;

            String eventType = data.has("e") ? data.get("e").asText() : "";
            if (!"aggTrade".equals(eventType)) return null;

            String symbol = data.get("s").asText();
            double price = Double.parseDouble(data.get("p").asText());
            double quantity = Double.parseDouble(data.get("q").asText());
            boolean isBuyerMaker = data.get("m").asBoolean();
            long tradeTimeMs = data.get("T").asLong();

            double valueUsd = price * quantity;
            if (valueUsd >= 10000) {
                LOG.debugf("Large trade: %s %s $%.0f (threshold: $%.0f)",
                        symbol, isBuyerMaker ? "SELL" : "BUY", valueUsd, getThreshold());
            }
            if (valueUsd < getThreshold()) return null;

            LOG.infof("WHALE TRADE: %s %s $%.0f @ $%.2f",
                    symbol, isBuyerMaker ? "SELL" : "BUY", valueUsd, price);

            return WhaleTransaction.fromBinanceTrade(symbol, price, quantity, isBuyerMaker, tradeTimeMs);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process Binance aggTrade message");
            return null;
        }
    }
}
