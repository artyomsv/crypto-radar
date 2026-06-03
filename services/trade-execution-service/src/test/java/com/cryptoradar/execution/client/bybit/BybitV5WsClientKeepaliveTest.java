package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.ws.ExecutionBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for the keepalive surface of {@link BybitV5WsClient}.
 *
 * <p>Booting Quarkus is not required — the keepalive logic is intentionally
 * isolated from CDI, the DB, and the broadcaster, so a hand-wired client with
 * a mocked {@link WebSocket} exercises every branch deterministically.
 */
class BybitV5WsClientKeepaliveTest {

    private BybitV5WsClient client;
    private WebSocket ws;
    private ExecutionBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        client = new BybitV5WsClient();
        client.mapper = new ObjectMapper();
        broadcaster = mock(ExecutionBroadcaster.class);
        client.broadcaster = broadcaster;
        ws = mock(WebSocket.class);
        when(ws.sendText(any(CharSequence.class), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(ws));
    }

    @Test
    void inboundPingFromServerProducesOutboundPong() {
        boolean handled = client.tryHandleKeepalive(ws, "{\"op\":\"ping\"}");

        assertTrue(handled, "ping frame must be handled by keepalive branch");
        ArgumentCaptor<CharSequence> sent = ArgumentCaptor.forClass(CharSequence.class);
        verify(ws).sendText(sent.capture(), eq(true));
        assertTrue(sent.getValue().toString().contains("\"op\":\"pong\""),
                "must reply with op:pong, got: " + sent.getValue());
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void inboundPongFromServerIsDroppedSilently() {
        boolean handled = client.tryHandleKeepalive(
                ws, "{\"op\":\"pong\",\"success\":true}");

        assertTrue(handled, "pong frame must be handled (and dropped) by keepalive branch");
        verify(ws, never()).sendText(any(CharSequence.class), anyBoolean());
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void normalTopicFrameFallsThroughKeepaliveBranch() {
        boolean handled = client.tryHandleKeepalive(
                ws, "{\"topic\":\"position\",\"data\":[]}");

        assertFalse(handled, "non-keepalive frame must fall through to handleMessage");
        verify(ws, never()).sendText(any(CharSequence.class), anyBoolean());
    }

    @Test
    void malformedFrameIsNotTreatedAsKeepalive() {
        boolean handled = client.tryHandleKeepalive(ws, "not json");

        assertFalse(handled, "malformed frame must not be claimed by keepalive branch");
        verify(ws, never()).sendText(any(CharSequence.class), anyBoolean());
    }

    @Test
    void scheduledPingTaskSendsPingFrame() throws Exception {
        // Shrink the interval so the recurring schedule fires inside the test
        // budget instead of waiting 20s. The static SCHEDULER is reused.
        client.pingIntervalMillis = 5L;

        ExchangeAccount account = new ExchangeAccount();
        account.setEnvironment("DEMO");
        BybitV5WsClient.Listener listener = client.new Listener(account);

        try {
            listener.startPingTask(ws);
            // Mockito timeout polls until the interaction occurs or the
            // budget elapses — much more reliable than Thread.sleep.
            verify(ws, timeout(TimeUnit.SECONDS.toMillis(2)).atLeastOnce())
                    .sendText(eq(BybitV5WsClient.PING_FRAME), eq(true));
        } finally {
            listener.cancelPingTask();
        }
    }

    @Test
    void cancelPingTaskStopsRecurringSends() throws Exception {
        client.pingIntervalMillis = 5L;
        ExchangeAccount account = new ExchangeAccount();
        account.setEnvironment("DEMO");
        BybitV5WsClient.Listener listener = client.new Listener(account);

        listener.startPingTask(ws);
        verify(ws, timeout(TimeUnit.SECONDS.toMillis(2)).atLeastOnce())
                .sendText(eq(BybitV5WsClient.PING_FRAME), eq(true));

        listener.cancelPingTask();
        // Allow any in-flight tick to complete, then snapshot the count and
        // re-verify after a short quiescent window.
        Thread.sleep(100);
        int afterCancel = mockingDetails(ws).pingFrameSendCount();
        Thread.sleep(200);
        int laterStill = mockingDetails(ws).pingFrameSendCount();
        assertTrue(laterStill - afterCancel <= 1,
                "ping sends must stop after cancelPingTask (delta=" + (laterStill - afterCancel) + ")");
    }

    private MockingHelper mockingDetails(WebSocket ws) {
        return new MockingHelper(ws);
    }

    /**
     * Tiny helper that counts how many times {@link WebSocket#sendText} was
     * invoked with the {@link BybitV5WsClient#PING_FRAME} payload. Avoids
     * pulling in Awaitility just to poll an invocation count.
     */
    private static final class MockingHelper {
        private final WebSocket ws;

        MockingHelper(WebSocket ws) {
            this.ws = ws;
        }

        int pingFrameSendCount() {
            int[] count = {0};
            org.mockito.MockingDetails details = org.mockito.Mockito.mockingDetails(ws);
            details.getInvocations().forEach(inv -> {
                if (!"sendText".equals(inv.getMethod().getName())) return;
                Object arg0 = inv.getArgument(0);
                if (arg0 != null && BybitV5WsClient.PING_FRAME.contentEquals(arg0.toString())) {
                    count[0]++;
                }
            });
            return count[0];
        }
    }
}
