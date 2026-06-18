package com.cryptoradar.derivatives.provider;

import com.cryptoradar.derivatives.client.BinanceFuturesClient;
import com.cryptoradar.derivatives.model.Liquidation;
import com.cryptoradar.derivatives.service.DerivativesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class BybitLiquidationProvider {

    private static final Logger LOG = Logger.getLogger(BybitLiquidationProvider.class);
    private static final String WS_URL = "wss://stream.bybit.com/v5/public/linear";

    @Inject
    DerivativesService derivativesService;

    @Inject
    BinanceFuturesClient futuresClient;

    @Inject
    ObjectMapper objectMapper;

    private volatile WebSocket webSocket;
    private volatile ScheduledExecutorService heartbeat;

    void onStartup(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor()
                .schedule(this::connect, 15, TimeUnit.SECONDS);
    }

    private void connect() {
        LOG.info("[Bybit Liquidations] Connecting...");
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL), new Listener())
                .thenAccept(ws -> {
                    this.webSocket = ws;

                    // Subscribe to liquidation topics for all tracked symbols
                    Set<String> symbols = futuresClient.getTrackedSymbols();
                    String args = symbols.stream()
                            .map(s -> "\"allLiquidation." + s + "\"")
                            .collect(Collectors.joining(","));
                    ws.sendText("{\"op\":\"subscribe\",\"args\":[" + args + "]}", true);

                    // Bybit requires ping every 20s to keep connection alive
                    if (heartbeat != null) heartbeat.shutdownNow();
                    heartbeat = Executors.newSingleThreadScheduledExecutor();
                    heartbeat.scheduleAtFixedRate(
                            () -> { if (webSocket != null) webSocket.sendText("{\"op\":\"ping\"}", true); },
                            20, 20, TimeUnit.SECONDS);

                    LOG.infof("[Bybit Liquidations] Connected, subscribed to %d symbols", symbols.size());
                })
                .exceptionally(ex -> {
                    LOG.warnf("[Bybit Liquidations] Connection failed: %s", ex.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        if (heartbeat != null) heartbeat.shutdownNow();
        Executors.newSingleThreadScheduledExecutor()
                .schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String topic = root.path("topic").asText();
            if (!topic.startsWith("allLiquidation.")) return;

            // allLiquidation pushes a data ARRAY of compact records: T=time(ms),
            // s=symbol, S=side, v=size, p=bankruptcy price (vs the deprecated
            // `liquidation` topic's single object with full field names).
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return;

            for (JsonNode item : data) {
                String symbol = item.path("s").asText();
                // Bybit reports the liquidated position side; v is base-asset size.
                String side = LiquidationNormalizer.liquidatedSide(
                        LiquidationNormalizer.BYBIT, item.path("S").asText());
                double price = Double.parseDouble(item.path("p").asText());
                double qty = Double.parseDouble(item.path("v").asText());
                long tsMs = item.path("T").asLong();

                Liquidation liq = new Liquidation(LiquidationNormalizer.BYBIT, symbol, side,
                        price, qty, price * qty, Instant.ofEpochMilli(tsMs));
                derivativesService.recordLiquidation(liq);
            }
        } catch (Exception e) {
            LOG.debugf("[Bybit Liquidations] Parse error: %s", e.getMessage());
        }
    }

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            LOG.warnf("[Bybit Liquidations] Closed: %d %s. Reconnecting...", code, reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(ws, code, reason);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.warnf("[Bybit Liquidations] Error: %s", error.getMessage());
            scheduleReconnect();
        }
    }
}
