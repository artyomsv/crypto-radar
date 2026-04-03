package com.cryptoradar.gateway.websocket;

import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Broadcasts messages to all connected WebSocket clients.
 * Replaces the SSE-based SseManager.
 */
@ApplicationScoped
public class WebSocketBroadcaster {

    private static final Logger LOG = Logger.getLogger(WebSocketBroadcaster.class);

    @Inject
    OpenConnections connections;

    public void broadcastPrices(String json) {
        String message = "{\"type\":\"prices\",\"data\":" + json + "}";
        broadcast(message);
    }

    public void broadcastNews(String json) {
        String message = "{\"type\":\"news\",\"data\":" + json + "}";
        broadcast(message);
    }

    public void broadcastAnalytics(String json) {
        String message = "{\"type\":\"analytics\",\"data\":" + json + "}";
        broadcast(message);
    }

    public void broadcastWhales(String json) {
        String message = "{\"type\":\"whales\",\"data\":" + json + "}";
        broadcast(message);
    }

    public void broadcastDerivatives(String json) {
        String message = "{\"type\":\"derivatives\",\"data\":" + json + "}";
        broadcast(message);
    }

    private void broadcast(String message) {
        connections.forEach(connection -> {
            connection.sendText(message).subscribe().with(
                    v -> {},
                    error -> LOG.warnf("Failed to send to %s: %s", connection.id(), error.getMessage())
            );
        });
    }
}
