package com.cryptoradar.execution.ws;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

/**
 * Server-side WebSocket endpoint that relays Bybit private-stream events
 * to connected frontend clients.
 *
 * <p>Registers / unregisters each session with {@link ExecutionBroadcaster}.
 * The broadcaster is the single fan-out point fed by
 * {@link com.cryptoradar.execution.client.bybit.BybitV5WsClient}.
 */
@ServerEndpoint("/ws/execution")
@ApplicationScoped
public class ExecutionWebSocket {

    private static final Logger LOG = Logger.getLogger(ExecutionWebSocket.class);

    @Inject ExecutionBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session) {
        broadcaster.register(session);
        LOG.infof("ws execution client opened: %s", session.getId());
    }

    @OnClose
    public void onClose(Session session) {
        broadcaster.unregister(session);
        LOG.infof("ws execution client closed: %s", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        LOG.warnf(error, "ws execution error on session %s", session.getId());
        broadcaster.unregister(session);
    }
}
