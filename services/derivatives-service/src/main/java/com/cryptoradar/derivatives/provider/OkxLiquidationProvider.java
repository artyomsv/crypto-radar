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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OkxLiquidationProvider {

    private static final Logger LOG = Logger.getLogger(OkxLiquidationProvider.class);
    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String SUBSCRIBE_MSG =
            "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"liquidation-orders\",\"instType\":\"SWAP\"}]}";
    private static final String INSTRUMENTS_URL =
            "https://www.okx.com/api/v5/public/instruments?instType=SWAP";

    @Inject
    DerivativesService derivativesService;

    @Inject
    BinanceFuturesClient futuresClient;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    // instId -> base-asset size of one contract (ctVal * ctMult). OKX reports
    // liquidation size in contracts, not base asset, so this is required to
    // compute a comparable USD notional.
    private final Map<String, Double> contractSizes = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private volatile ScheduledExecutorService heartbeat;

    void onStartup(@Observes StartupEvent event) {
        loadContractSizes();
        Executors.newSingleThreadScheduledExecutor()
                .schedule(this::connect, 10, TimeUnit.SECONDS);
    }

    /**
     * Fetches the SWAP instrument catalogue once so liquidation contract counts
     * can be converted to base-asset quantity. Fail-open: on error the map stays
     * empty and liquidations for unknown instruments are skipped (better than
     * storing a wrong notional).
     */
    private void loadContractSizes() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(INSTRUMENTS_URL))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode data = objectMapper.readTree(resp.body()).path("data");
            if (!data.isArray()) return;
            for (JsonNode inst : data) {
                double ctVal = parseOrZero(inst.path("ctVal").asText());
                double ctMult = parseOrZero(inst.path("ctMult").asText());
                if (ctVal > 0 && ctMult > 0) {
                    contractSizes.put(inst.path("instId").asText(), ctVal * ctMult);
                }
            }
            LOG.infof("[OKX Liquidations] Loaded contract sizes for %d instruments", contractSizes.size());
        } catch (Exception e) {
            LOG.warnf("[OKX Liquidations] Failed to load contract sizes: %s", e.getMessage());
        }
    }

    private static double parseOrZero(String s) {
        try { return s == null || s.isEmpty() ? 0 : Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0; }
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

            // Surface subscribe acks/errors instead of silently waiting for data
            // that never arrives — a malformed subscription used to fail invisibly.
            String event = root.path("event").asText("");
            if ("error".equals(event)) {
                LOG.warnf("[OKX Liquidations] Subscribe rejected: %s", message);
                return;
            }
            if ("subscribe".equals(event)) {
                LOG.infof("[OKX Liquidations] Subscription confirmed: %s", root.path("arg"));
                return;
            }

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return;

            for (JsonNode item : data) {
                String instId = item.path("instId").asText();
                // Map "BTC-USDT-SWAP" -> "BTCUSDT"
                String symbol = instId.replace("-SWAP", "").replace("-", "");

                // OKX streams all SWAP liquidations market-wide; scope to the
                // symbols we track (Binance/Bybit are already scoped) so the
                // table isn't flooded with exotic alts we never trade.
                if (!futuresClient.getTrackedSymbols().contains(symbol)) continue;

                Double contractSize = contractSizes.get(instId);
                if (contractSize == null) {
                    LOG.debugf("[OKX Liquidations] No contract size for %s — skipping", instId);
                    continue;
                }

                JsonNode details = item.get("details");
                if (details == null || !details.isArray()) continue;

                for (JsonNode detail : details) {
                    // OKX reports the liquidation order side; sz is in contracts;
                    // the price field is bkPx (bankruptcy price), not px.
                    String side = LiquidationNormalizer.liquidatedSide(
                            LiquidationNormalizer.OKX, detail.path("side").asText());
                    double price = Double.parseDouble(detail.path("bkPx").asText());
                    double contracts = Double.parseDouble(detail.path("sz").asText());
                    double qty = LiquidationNormalizer.contractsToBaseQty(contracts, contractSize, 1.0);
                    long tsMs = Long.parseLong(detail.path("ts").asText());

                    Liquidation liq = new Liquidation(LiquidationNormalizer.OKX, symbol, side,
                            price, qty, price * qty, Instant.ofEpochMilli(tsMs));
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
