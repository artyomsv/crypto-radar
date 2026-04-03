package com.cryptoradar.whale.provider.exchange;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.service.WhaleFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base for exchange WebSocket trade stream providers.
 * Handles connection lifecycle, reconnection, and message buffering.
 * Subclasses implement: getWsUrl(), getSubscribeMessage(), parseTradeMessage().
 */
public abstract class AbstractExchangeStreamProvider implements ExchangeTradeStreamProvider {

    private static final Logger LOG = Logger.getLogger(AbstractExchangeStreamProvider.class);
    private static final long RECONNECT_DELAY_SECONDS = 5;

    @ConfigProperty(name = "whale.threshold.usd")
    double whaleThresholdUsd;

    @Inject
    WhaleFlowService whaleFlowService;

    @Inject
    ObjectMapper objectMapper;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    protected volatile WebSocket webSocket;
    private volatile ScheduledExecutorService heartbeatExecutor;

    /** Full WebSocket URL to connect to */
    protected abstract String getWsUrl();

    /** Optional subscribe message to send after connection opens. Return null if not needed. */
    protected abstract String getSubscribeMessage();

    /** Parse a WebSocket message and return a WhaleTransaction if it's a large trade, null otherwise. */
    protected abstract WhaleTransaction parseTradeMessage(String message);

    /** Called after WebSocket connection is established and initial subscribe message is sent. Override to send additional messages. */
    protected void onConnected(WebSocket ws) {
        // Default no-op — subclasses can override to send additional subscribe messages
    }

    protected double getThreshold() {
        return whaleThresholdUsd;
    }

    protected ObjectMapper mapper() {
        return objectMapper;
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void disconnect() {
        shutdownHeartbeatExecutor();
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
            }
        }
        connected.set(false);
    }

    private void shutdownHeartbeatExecutor() {
        ScheduledExecutorService executor = heartbeatExecutor;
        if (executor != null) {
            executor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    /** Override to provide a ping message (e.g. Bybit needs {"op":"ping"}) */
    protected String getPingMessage() { return null; }

    /** Ping interval in seconds. Override if exchange needs custom interval. */
    protected int getPingIntervalSeconds() { return 20; }

    @Override
    public void connect() {
        String url = getWsUrl();
        LOG.infof("[%s] Connecting to WebSocket: %s", getExchangeName(), url);

        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
                .buildAsync(URI.create(url), new Listener())
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    connected.set(true);
                    LOG.infof("[%s] WebSocket connected", getExchangeName());

                    // Start heartbeat if configured — shut down previous executor first
                    String pingMsg = getPingMessage();
                    if (pingMsg != null) {
                        shutdownHeartbeatExecutor();
                        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
                        heartbeatExecutor = executor;
                        executor.scheduleAtFixedRate(() -> {
                            if (connected.get() && webSocket != null) {
                                webSocket.sendText(pingMsg, true);
                            }
                        }, getPingIntervalSeconds(), getPingIntervalSeconds(), TimeUnit.SECONDS);
                    }

                    String subMsg = getSubscribeMessage();
                    if (subMsg != null) {
                        ws.sendText(subMsg, true);
                        LOG.debugf("[%s] Sent subscribe message", getExchangeName());
                    }

                    onConnected(ws);
                })
                .exceptionally(ex -> {
                    LOG.errorf("[%s] Connection failed: %s", getExchangeName(), ex.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        connected.set(false);
        shutdownHeartbeatExecutor();
        ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
        reconnectExecutor.schedule(() -> {
            reconnectExecutor.shutdown();
            connect();
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    protected void processTransaction(WhaleTransaction tx) {
        if (tx != null && tx.getValueUsd() >= whaleThresholdUsd) {
            whaleFlowService.processWhaleTransaction(tx);
        }
    }

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    WhaleTransaction tx = parseTradeMessage(message);
                    processTransaction(tx);
                } catch (Exception e) {
                    LOG.debugf("[%s] Parse error: %s", getExchangeName(), e.getMessage());
                }
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOG.warnf("[%s] WebSocket closed: %d %s. Reconnecting...",
                    getExchangeName(), statusCode, reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.errorf("[%s] WebSocket error: %s. Reconnecting...",
                    getExchangeName(), error.getMessage());
            scheduleReconnect();
        }
    }
}
