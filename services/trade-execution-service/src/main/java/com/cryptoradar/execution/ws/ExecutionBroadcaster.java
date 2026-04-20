package com.cryptoradar.execution.ws;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.Session;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Pub/sub fan-out for execution-service WebSocket clients.
 *
 * <p>{@link ExecutionWebSocket} registers each connected browser session here;
 * {@link com.cryptoradar.execution.client.bybit.BybitV5WsClient} forwards every
 * raw Bybit private-stream message via {@link #broadcast(String)}.
 *
 * <p>Sessions are held in a {@link CopyOnWriteArraySet} so iteration during a
 * broadcast is safe against concurrent add/remove from the server endpoint
 * lifecycle methods.
 */
@ApplicationScoped
public class ExecutionBroadcaster {

    private static final Logger LOG = Logger.getLogger(ExecutionBroadcaster.class);

    private final Set<Session> sessions = new CopyOnWriteArraySet<>();

    public void register(Session session) {
        sessions.add(session);
        LOG.debugf("ws client registered: %s (total=%d)", session.getId(), sessions.size());
    }

    public void unregister(Session session) {
        sessions.remove(session);
        LOG.debugf("ws client unregistered: %s (total=%d)", session.getId(), sessions.size());
    }

    public void broadcast(String message) {
        for (Session s : sessions) {
            if (!s.isOpen()) continue;
            try {
                s.getBasicRemote().sendText(message);
            } catch (IOException e) {
                LOG.warnf(e, "ws send failed for session %s — dropping", s.getId());
                sessions.remove(s);
            }
        }
    }

    public int sessionCount() {
        return sessions.size();
    }
}
