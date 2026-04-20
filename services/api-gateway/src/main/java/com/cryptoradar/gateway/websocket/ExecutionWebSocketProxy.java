package com.cryptoradar.gateway.websocket;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket proxy that bridges the browser client at <code>/ws/execution</code>
 * to the upstream <code>trade-execution-service</code>'s own WS endpoint.
 *
 * <p>Uses the Quarkus websockets-next API on the server side (matching the rest
 * of this gateway) and the JDK {@link java.net.http.WebSocket} on the client side
 * to avoid pulling in a second WS server extension.
 */
@WebSocket(path = "/ws/execution")
public class ExecutionWebSocketProxy {

    private static final Logger LOG = Logger.getLogger(ExecutionWebSocketProxy.class);

    @ConfigProperty(name = "execution.ws.url")
    String upstreamUrl;

    @Inject
    WebSocketConnection connection;

    private final AtomicReference<java.net.http.WebSocket> upstream = new AtomicReference<>();
    private final StringBuilder partial = new StringBuilder();

    @OnOpen
    public void onOpen() {
        String connectionId = connection.id();
        LOG.infof("ws execution gateway client opened: %s -> %s", connectionId, upstreamUrl);
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(upstreamUrl), new UpstreamListener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        LOG.warnf(err, "execution upstream WS connect failed for %s", connectionId);
                        try {
                            connection.closeAndAwait();
                        } catch (RuntimeException e) {
                            LOG.debugf(e, "client session already closing on upstream-connect failure");
                        }
                    } else {
                        upstream.set(ws);
                    }
                });
    }

    @OnTextMessage
    public void onMessage(String msg) {
        java.net.http.WebSocket up = upstream.get();
        if (up != null) {
            up.sendText(msg, true);
        }
    }

    @OnClose
    public void onClose() {
        LOG.infof("ws execution gateway client closed: %s", connection.id());
        java.net.http.WebSocket up = upstream.getAndSet(null);
        if (up != null) {
            up.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "client-closed");
        }
    }

    /**
     * JDK WebSocket listener for the upstream connection. Forwards every text
     * frame back down to the browser via {@link WebSocketConnection#sendText}.
     */
    private class UpstreamListener implements java.net.http.WebSocket.Listener {

        @Override
        public CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String payload = partial.toString();
                partial.setLength(0);
                try {
                    connection.sendText(payload).subscribe().with(
                            v -> { /* no-op */ },
                            err -> LOG.warnf(err, "gateway WS send to client failed"));
                } catch (RuntimeException e) {
                    LOG.warnf(e, "gateway WS send to client threw");
                }
            }
            return java.net.http.WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(java.net.http.WebSocket ws, int status, String reason) {
            LOG.debugf("upstream WS closed status=%d reason=%s", status, reason);
            try {
                connection.closeAndAwait();
            } catch (RuntimeException e) {
                LOG.debugf(e, "client session already closing");
            }
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket ws, Throwable error) {
            LOG.warnf(error, "upstream WS listener error");
        }
    }
}
