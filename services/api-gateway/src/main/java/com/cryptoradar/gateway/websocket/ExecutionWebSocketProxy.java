package com.cryptoradar.gateway.websocket;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket proxy that bridges each connected browser client at
 * <code>/ws/execution</code> to its own upstream connection into the
 * <code>trade-execution-service</code>'s private-stream endpoint.
 *
 * <p>Uses Quarkus websockets-next on the server leg (matching the rest of this
 * gateway) and the JDK {@link java.net.http.WebSocket} on the client leg to
 * avoid pulling in a second WS server extension.
 *
 * <p>This class is {@code @ApplicationScoped} — a single CDI instance handles
 * ALL connected clients. Per-session state MUST therefore be keyed by
 * {@link WebSocketConnection#id()}; never stored in plain instance fields.
 */
@WebSocket(path = "/ws/execution")
@ApplicationScoped
public class ExecutionWebSocketProxy {

    private static final Logger LOG = Logger.getLogger(ExecutionWebSocketProxy.class);

    @ConfigProperty(name = "execution.ws.url")
    String upstreamUrl;

    /** One upstream JDK WebSocket per connected browser client, keyed by {@link WebSocketConnection#id()}. */
    private final ConcurrentHashMap<String, java.net.http.WebSocket> upstreams = new ConcurrentHashMap<>();

    /** Per-connection partial-frame buffers for assembling fragmented upstream text frames. */
    private final ConcurrentHashMap<String, StringBuilder> partials = new ConcurrentHashMap<>();

    /** Shared across all connections — creating a new one per onOpen leaked an executor pool per client. */
    private final HttpClient http = HttpClient.newHttpClient();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        String id = connection.id();
        LOG.infof("ws execution gateway client opened: %s -> %s", id, upstreamUrl);
        partials.put(id, new StringBuilder());
        http.newWebSocketBuilder()
                .buildAsync(URI.create(upstreamUrl), new UpstreamListener(connection))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        LOG.warnf(err, "execution upstream WS connect failed for session %s", id);
                        partials.remove(id);
                        try {
                            connection.closeAndAwait();
                        } catch (RuntimeException e) {
                            LOG.debugf(e, "client session already closing on upstream-connect failure");
                        }
                    } else {
                        upstreams.put(id, ws);
                    }
                });
    }

    @OnTextMessage
    public void onMessage(String msg, WebSocketConnection connection) {
        java.net.http.WebSocket up = upstreams.get(connection.id());
        if (up != null) {
            up.sendText(msg, true);
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        String id = connection.id();
        LOG.infof("ws execution gateway client closed: %s", id);
        partials.remove(id);
        java.net.http.WebSocket up = upstreams.remove(id);
        if (up != null) {
            try {
                up.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "client-closed");
            } catch (RuntimeException e) {
                LOG.debugf(e, "upstream close failed for session %s", id);
            }
        }
    }

    @OnError
    public void onError(Throwable error, WebSocketConnection connection) {
        String id = connection == null ? "<unknown>" : connection.id();
        LOG.warnf(error, "gateway WS error for session %s", id);
    }

    /**
     * JDK WebSocket listener for one upstream connection. Forwards every text
     * frame back to its paired browser client and cleans up partial buffers
     * via the outer proxy's per-session map.
     */
    private class UpstreamListener implements java.net.http.WebSocket.Listener {

        private final WebSocketConnection client;

        UpstreamListener(WebSocketConnection client) {
            this.client = client;
        }

        @Override
        public CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
            StringBuilder partial = partials.get(client.id());
            if (partial == null) {
                // Session already closed — drop the frame, keep the ack flowing upstream.
                return java.net.http.WebSocket.Listener.super.onText(ws, data, last);
            }
            partial.append(data);
            if (last) {
                String payload = partial.toString();
                partial.setLength(0);
                if (client.isOpen()) {
                    forwardToClient(payload);
                }
            }
            return java.net.http.WebSocket.Listener.super.onText(ws, data, last);
        }

        private void forwardToClient(String payload) {
            try {
                client.sendText(payload).subscribe().with(
                        v -> { /* sent */ },
                        err -> LOG.warnf(err, "gateway WS send to client %s failed", client.id()));
            } catch (RuntimeException e) {
                LOG.warnf(e, "gateway WS send to client %s threw", client.id());
            }
        }

        @Override
        public CompletionStage<?> onClose(java.net.http.WebSocket ws, int status, String reason) {
            LOG.debugf("upstream WS closed for session %s status=%d reason=%s", client.id(), status, reason);
            if (client.isOpen()) {
                try {
                    client.closeAndAwait();
                } catch (RuntimeException e) {
                    LOG.debugf(e, "client session already closing for %s", client.id());
                }
            }
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket ws, Throwable error) {
            LOG.warnf(error, "upstream WS listener error for session %s", client.id());
        }
    }
}
