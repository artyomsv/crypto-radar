package com.cryptoradar.whale.provider.binance;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.service.WhaleFlowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class BinanceTradeStreamProvider {

    private static final Logger LOG = Logger.getLogger(BinanceTradeStreamProvider.class);

    private static final List<String> SYMBOLS = List.of(
            "btcusdt", "ethusdt", "bnbusdt", "solusdt", "xrpusdt",
            "adausdt", "avaxusdt", "dotusdt", "linkusdt", "dogeusdt"
    );

    private static final long RECONNECT_DELAY_SECONDS = 5;

    @ConfigProperty(name = "whale.binance.ws.url")
    String baseWsUrl;

    @ConfigProperty(name = "whale.threshold.usd")
    double whaleThresholdUsd;

    @Inject
    WhaleFlowService whaleFlowService;

    @Inject
    ObjectMapper objectMapper;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile WebSocket webSocket;

    void onStart(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor().schedule(
                this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isConnected() {
        return connected.get();
    }

    private void connect() {
        String streams = SYMBOLS.stream()
                .map(s -> s + "@aggTrade")
                .collect(Collectors.joining("/"));
        String url = baseWsUrl + "?streams=" + streams;

        LOG.infof("Connecting to Binance WebSocket: %s", url);

        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
                .buildAsync(URI.create(url), new BinanceWebSocketListener())
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    connected.set(true);
                    LOG.info("Connected to Binance aggTrade stream");
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to connect to Binance WebSocket");
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        connected.set(false);
        LOG.infof("Scheduling reconnect in %d seconds", RECONNECT_DELAY_SECONDS);
        Executors.newSingleThreadScheduledExecutor().schedule(
                this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.get("data");
            if (data == null) return;

            String eventType = data.has("e") ? data.get("e").asText() : "";
            if (!"aggTrade".equals(eventType)) return;

            String symbol = data.get("s").asText();
            double price = Double.parseDouble(data.get("p").asText());
            double quantity = Double.parseDouble(data.get("q").asText());
            boolean isBuyerMaker = data.get("m").asBoolean();
            long tradeTimeMs = data.get("T").asLong();

            double valueUsd = price * quantity;
            if (valueUsd >= 10000) {
                LOG.debugf("Large trade: %s %s $%.0f (threshold: $%.0f)",
                        symbol, isBuyerMaker ? "SELL" : "BUY", valueUsd, whaleThresholdUsd);
            }
            if (valueUsd < whaleThresholdUsd) return;
            LOG.infof("WHALE TRADE: %s %s $%.0f @ $%.2f",
                    symbol, isBuyerMaker ? "SELL" : "BUY", valueUsd, price);

            WhaleTransaction tx = WhaleTransaction.fromBinanceTrade(
                    symbol, price, quantity, isBuyerMaker, tradeTimeMs);
            whaleFlowService.processWhaleTransaction(tx);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process Binance aggTrade message");
        }
    }

    private class BinanceWebSocketListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.info("Binance WebSocket connection opened");
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.warnf("Binance WebSocket closed: status=%d reason=%s", statusCode, reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.errorf(error, "Binance WebSocket error");
            scheduleReconnect();
        }
    }
}
