package com.cryptoradar.derivatives.provider;

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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OkxLiquidationProvider {

    private static final Logger LOG = Logger.getLogger(OkxLiquidationProvider.class);
    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String SUBSCRIBE_MSG =
            "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"liquidation-orders\",\"instType\":\"SWAP\"}]}";

    @Inject
    DerivativesService derivativesService;

    @Inject
    ObjectMapper objectMapper;

    private volatile WebSocket webSocket;
    private volatile ScheduledExecutorService heartbeat;

    void onStartup(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor()
                .schedule(this::connect, 10, TimeUnit.SECONDS);
    }

    private void connect() {
        LOG.info("[OKX Liquidations] Connecting...");
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL), new Listener())
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    ws.sendText(SUBSCRIBE_MSG, true);

                    // OKX requires ping every 20s to keep connection alive
                    if (heartbeat != null) heartbeat.shutdownNow();
                    heartbeat = Executors.newSingleThreadScheduledExecutor();
                    heartbeat.scheduleAtFixedRate(
                            () -> { if (webSocket != null) webSocket.sendText("ping", true); },
                            20, 20, TimeUnit.SECONDS);

                    LOG.info("[OKX Liquidations] Connected and subscribed");
                })
                .exceptionally(ex -> {
                    LOG.warnf("[OKX Liquidations] Connection failed: %s", ex.getMessage());
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
            if ("pong".equals(message)) return;

            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return;

            for (JsonNode item : data) {
                String instId = item.path("instId").asText();
                // Map "BTC-USDT-SWAP" -> "BTCUSDT"
                String symbol = instId.replace("-SWAP", "").replace("-", "");

                JsonNode details = item.get("details");
                if (details == null || !details.isArray()) continue;

                for (JsonNode detail : details) {
                    String side = detail.path("side").asText().toUpperCase();
                    double price = Double.parseDouble(detail.path("px").asText());
                    double qty = Double.parseDouble(detail.path("sz").asText());
                    long tsMs = Long.parseLong(detail.path("ts").asText());

                    Liquidation liq = new Liquidation(
                            symbol, side, price, qty, price * qty, Instant.ofEpochMilli(tsMs));
                    derivativesService.recordLiquidation(liq);
                }
            }
        } catch (Exception e) {
            LOG.debugf("[OKX Liquidations] Parse error: %s", e.getMessage());
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
            LOG.warnf("[OKX Liquidations] Closed: %d %s. Reconnecting...", code, reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(ws, code, reason);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.warnf("[OKX Liquidations] Error: %s", error.getMessage());
            scheduleReconnect();
        }
    }
}
