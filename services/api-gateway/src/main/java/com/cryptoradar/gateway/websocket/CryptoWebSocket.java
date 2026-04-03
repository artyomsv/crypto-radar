package com.cryptoradar.gateway.websocket;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@WebSocket(path = "/ws/crypto")
public class CryptoWebSocket {

    private static final Logger LOG = Logger.getLogger(CryptoWebSocket.class);

    @Inject
    WebSocketConnection connection;

    @OnOpen
    public String onOpen() {
        LOG.infof("WebSocket client connected: %s", connection.id());
        return "{\"type\":\"connected\",\"message\":\"CryptoRadar WebSocket connected\"}";
    }

    @OnClose
    public void onClose() {
        LOG.infof("WebSocket client disconnected: %s", connection.id());
    }

    @OnTextMessage
    public String onMessage(String message) {
        LOG.debugf("Received message from %s: %s", connection.id(), message);
        // Future: handle subscribe/unsubscribe, alerts, watchlists
        return "{\"type\":\"ack\",\"message\":\"received\"}";
    }
}
