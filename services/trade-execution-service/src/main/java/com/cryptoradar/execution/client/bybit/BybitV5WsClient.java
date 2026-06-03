package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.notify.ExecutionEventService;
import com.cryptoradar.execution.lifecycle.OrderReconciler;
import com.cryptoradar.execution.observability.SlippageLogger;
import com.cryptoradar.execution.security.CredentialCipher;
import com.cryptoradar.execution.ws.ExecutionBroadcaster;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bybit V5 private-stream WebSocket client.
 *
 * <p>On startup, opens one connection per {@link ExchangeAccount}, authenticates
 * via HMAC-signed {@code GET/realtime{expires}}, and subscribes to the
 * {@code position}, {@code execution}, {@code order} and {@code wallet} topics.
 *
 * <p>Every inbound message is:
 * <ol>
 *   <li>parsed and dispatched to a topic-specific DB update ({@code position}
 *       closes trades when size=0; {@code execution} records first-fill entry
 *       price);</li>
 *   <li>forwarded verbatim to {@link ExecutionBroadcaster} for frontend
 *       relay.</li>
 * </ol>
 *
 * <p>Reconnects on disconnect with 1→30s exponential backoff, reusing a single
 * static daemon scheduler (one thread pool per JVM, not per attempt).
 */
@ApplicationScoped
public class BybitV5WsClient {

    private static final Logger LOG = Logger.getLogger(BybitV5WsClient.class);

    private static final long AUTH_EXPIRES_MS = 10_000L;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final long STARTUP_DELAY_SECONDS = 15L;
    /**
     * Backoff between {@link #safeConnectAll()} retries when the initial DB
     * read fails (typical cause: TimescaleDB still booting). 30s is shorter
     * than Bybit's WS keep-alive window so the live trader recovers fast
     * after a host restart.
     */
    private static final long CONNECT_RETRY_SECONDS = 30L;
    private static final long RECONNECT_DELAY_SECONDS = 2L;

    /**
     * Bybit V5 idle-times out a private connection after ~30s without a frame
     * exchange. Sending an application-level ping every 20s keeps the
     * connection alive so position / execution / wallet frames don't get lost
     * in reconnect gaps. Package-private so tests can shrink the interval.
     */
    static final String PING_FRAME = "{\"op\":\"ping\"}";
    static final String PONG_FRAME = "{\"op\":\"pong\"}";
    static final String OP_PING = "ping";
    static final String OP_PONG = "pong";

    private static final String TOPIC_POSITION = "position";
    private static final String TOPIC_EXECUTION = "execution";
    private static final String TOPIC_ORDER = "order";
    private static final String TOPIC_WALLET = "wallet";

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bybit-ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventService events;
    @Inject ExecutionBroadcaster broadcaster;
    @Inject OrderReconciler reconciler;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Outbound ping cadence in milliseconds. Package-private and mutable so
     * unit tests can shrink it without booting Quarkus or introducing an
     * external config property. Default 20s matches Bybit's documented
     * keep-alive contract.
     */
    long pingIntervalMillis = TimeUnit.SECONDS.toMillis(20L);

    void onStart(@Observes StartupEvent ev) {
        SCHEDULER.schedule(this::safeConnectAll, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Entry point for the scheduler thread. Activates a CDI request context so
     * Panache / Hibernate can see a managed {@code EntityManager} when
     * {@link #connectAll()} queries the DB, and catches any exception the
     * scheduler would otherwise swallow silently.
     */
    void safeConnectAll() {
        ManagedContext reqContext = Arc.container().requestContext();
        boolean activatedHere = false;
        if (!reqContext.isActive()) {
            reqContext.activate();
            activatedHere = true;
        }
        try {
            connectAll();
        } catch (RuntimeException e) {
            // Most common cause: DB cold-start race on container boot —
            // accountRepo.listAll() throws before Hibernate is ready.
            // Without rescheduling, the WS subsystem stays dead permanently
            // (live-trader observed silent for 3.5h after a 2026-05-26 boot
            // race). Retry until connectAll succeeds at least once.
            LOG.errorf(e, "BybitV5WsClient connectAll failed — retrying in %ds",
                    CONNECT_RETRY_SECONDS);
            SCHEDULER.schedule(this::safeConnectAll, CONNECT_RETRY_SECONDS, TimeUnit.SECONDS);
        } finally {
            if (activatedHere) reqContext.terminate();
        }
    }

    void connectAll() {
        List<ExchangeAccount> accounts = accountRepo.listAll();
        LOG.infof("BybitV5WsClient starting — %d account(s)", accounts.size());
        accounts.forEach(this::connect);
    }

    void connect(ExchangeAccount account) {
        connectWithBackoff(account, INITIAL_BACKOFF_MS);
    }

    private void connectWithBackoff(ExchangeAccount account, long backoffMs) {
        String url = BybitV5Endpoints.wsPrivateFor(account.getEnvironment());
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new Listener(account))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        long next = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                        LOG.warnf(err, "WS connect failed for account %d — retry in %dms",
                                account.getId(), backoffMs);
                        SCHEDULER.schedule(() -> connectWithBackoff(account, next),
                                backoffMs, TimeUnit.MILLISECONDS);
                    }
                });
    }

    /**
     * Parse one Bybit private-stream frame, apply topic-specific DB updates,
     * and forward the raw payload to the broadcaster.
     *
     * <p>Runs in its own transaction on every call so DB writes inside
     * {@link #handlePosition} / {@link #handleExecution} commit independently
     * of the WebSocket listener thread. Package-private so unit tests and the
     * inner {@link Listener} can invoke it while the interceptor still fires.
     */
    @Transactional
    public void handleMessage(ExchangeAccount account, String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            String topic = root.path("topic").asText(null);
            if (topic == null) {
                broadcaster.broadcast(raw);
                return;
            }
            JsonNode dataArr = root.path("data");
            if (dataArr.isArray()) {
                for (JsonNode item : dataArr) {
                    dispatchTopic(account, topic, item);
                }
            }
            broadcaster.broadcast(raw);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "WS message parse error for account %d", account.getId());
        } catch (RuntimeException e) {
            LOG.warnf(e, "WS handler error for account %d", account.getId());
        }
    }

    /**
     * Inspect a raw frame and handle it as keepalive if applicable.
     *
     * <ul>
     *   <li>{@code op:"ping"} from server → reply with {@code op:"pong"}.</li>
     *   <li>{@code op:"pong"} from server (response to our ping) → drop
     *       silently; the frontend doesn't need to see keepalive noise.</li>
     * </ul>
     *
     * @return {@code true} if the frame was a keepalive and is fully handled
     *         (caller should NOT continue to {@link #handleMessage}); {@code
     *         false} otherwise.
     */
    boolean tryHandleKeepalive(WebSocket ws, String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return false;
        }
        String op = root.path("op").asText(null);
        if (OP_PING.equals(op)) {
            ws.sendText(PONG_FRAME, true);
            return true;
        }
        if (OP_PONG.equals(op)) {
            return true;
        }
        return false;
    }

    private void dispatchTopic(ExchangeAccount account, String topic, JsonNode item) {
        switch (topic) {
            case TOPIC_POSITION -> handlePosition(account, item);
            case TOPIC_EXECUTION -> handleExecution(account, item);
            case TOPIC_ORDER, TOPIC_WALLET -> { /* pass-through only */ }
            default -> { /* ignore unknown topics */ }
        }
    }

    private void handlePosition(ExchangeAccount account, JsonNode pos) {
        String symbol = pos.path("symbol").asText(null);
        BigDecimal size = parseBd(pos.path("size").asText(null));
        if (symbol == null || size == null) return;

        // Position closed externally (size went to zero). Find any open trade
        // on this account+symbol and mark it CLOSED.
        if (size.compareTo(BigDecimal.ZERO) != 0) return;

        List<ExecutedTrade> openTrades = tradeRepo.findOpenForAccount(account.getId());
        for (ExecutedTrade t : openTrades) {
            if (!symbol.equals(t.getSymbol())) continue;
            if (t.getStatus() == TradeStatus.CLOSED) continue;
            // Delegate to the reconciler so PnL, fees, exit price/reason and
            // R-multiple all get populated in one place. closeFromReconcile
            // queries Bybit's closed-pnl endpoint inline. If that fails (rate
            // limit, network, or no matching close yet), fall back to a bare
            // CLOSED mark — the periodic reconciler / admin backfill will
            // recover the missing fields on a later pass.
            boolean populated = reconciler.closeFromReconcile(account, t);
            if (!populated) {
                t.setStatus(TradeStatus.CLOSED);
                t.setClosedAt(Instant.now());
                t.setLastSyncAt(Instant.now());
                tradeRepo.persist(t);
            }
            events.record(makeEvent(
                    account.getId(), ExecutionEventType.POSITION_CLOSED,
                    Map.of(
                            "symbol", symbol,
                            "tradeId", t.getId(),
                            "source", "ws_position",
                            "metadataPopulated", populated
                    )));
        }
    }

    private void handleExecution(ExchangeAccount account, JsonNode exec) {
        String orderLinkId = exec.path("orderLinkId").asText(null);
        if (orderLinkId == null || orderLinkId.isEmpty()) return;

        Optional<ExecutedTrade> maybe = tradeRepo.findByOrderLinkId(orderLinkId);
        if (maybe.isEmpty()) return;
        ExecutedTrade trade = maybe.get();

        BigDecimal execPrice = parseBd(exec.path("execPrice").asText(null));
        BigDecimal execQty = parseBd(exec.path("execQty").asText(null));
        String execType = exec.path("execType").asText(null);

        // First-fill entry refinement: OrderPlacer pre-fills with the intended
        // entry; the very first execution event refines to the actual fill
        // price. Subsequent partial fills don't overwrite. We use lastSyncAt
        // as the "haven't seen any WS event yet" marker, since entryPrice is
        // now non-null even before the WS event arrives.
        if (trade.getLastSyncAt() == null && execPrice != null) {
            // intended = signal-side entry pre-filled by OrderPlacer; actual =
            // first WS fill. Log BEFORE overwriting so both values exist.
            SlippageLogger.logEntry(trade, trade.getEntryPrice(), execPrice);
            trade.setEntryPrice(execPrice);
            trade.setLastSyncAt(Instant.now());
            if (trade.getStatus() == TradeStatus.PENDING_PLACE) {
                trade.setStatus(TradeStatus.OPEN);
            }
            tradeRepo.persist(trade);
        }

        events.record(makeEvent(
                account.getId(), ExecutionEventType.ORDER_FILLED,
                Map.of(
                        "orderLinkId", orderLinkId,
                        "symbol", trade.getSymbol(),
                        "execType", execType == null ? "" : execType,
                        "execPrice", execPrice == null ? "" : execPrice.toPlainString(),
                        "execQty", execQty == null ? "" : execQty.toPlainString(),
                        "tradeId", trade.getId()
                )));
    }

    private ExecutionEvent makeEvent(Long accountId, ExecutionEventType type,
                                     Map<String, Object> metadata) {
        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(accountId);
        ev.setEventType(type);
        ev.setMetadata(metadata);
        return ev;
    }

    private static BigDecimal parseBd(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    /**
     * JDK HttpClient WebSocket.Listener that dispatches to the outer
     * {@link BybitV5WsClient#handleMessage} so the {@code @Transactional}
     * interceptor fires — inner-class method interceptors are never invoked
     * by CDI, which is why the handler lives on the outer class.
     */
    class Listener implements WebSocket.Listener {

        private final ExchangeAccount account;
        private final StringBuilder partial = new StringBuilder();
        private ScheduledFuture<?> pingTask;

        Listener(ExchangeAccount account) {
            this.account = account;
        }

        @Override
        public void onOpen(WebSocket ws) {
            LOG.infof("Bybit WS connected for account %d (%s)",
                    account.getId(), account.getEnvironment());
            try {
                authenticate(ws);
                subscribe(ws);
                startPingTask(ws);
            } catch (RuntimeException e) {
                LOG.errorf(e, "WS open handler failed for account %d", account.getId());
            } catch (Exception e) {
                LOG.errorf(e, "WS open handler checked exception for account %d",
                        account.getId());
            }
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String msg = partial.toString();
                partial.setLength(0);
                // Branch BEFORE handleMessage so keepalive frames don't open a
                // transaction and aren't broadcast to the frontend.
                if (!BybitV5WsClient.this.tryHandleKeepalive(ws, msg)) {
                    BybitV5WsClient.this.handleMessage(account, msg);
                }
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOG.warnf("Bybit WS closed for account %d — status=%d reason=%s",
                    account.getId(), statusCode, reason);
            cancelPingTask();
            // NOTE: deliberately not persisting a WS_DISCONNECTED event here —
            // the listener thread has no active transaction, and wrapping this
            // in a synthetic @Transactional call would complicate the reconnect
            // path. Log suffices; a lifecycle-audit task can be added later.
            SCHEDULER.schedule(
                    () -> connect(account),
                    RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.errorf(error, "Bybit WS error for account %d", account.getId());
            // The JDK WebSocket contract does NOT guarantee onClose follows a
            // transport-level onError, so cancel the ping schedule here too —
            // otherwise we leak a recurring task that sends pings into a dead
            // connection until the JVM exits.
            cancelPingTask();
        }

        void startPingTask(WebSocket ws) {
            cancelPingTask();
            long interval = Math.max(1L, pingIntervalMillis);
            pingTask = SCHEDULER.scheduleAtFixedRate(
                    () -> sendPingSafely(ws),
                    interval, interval, TimeUnit.MILLISECONDS);
        }

        void cancelPingTask() {
            ScheduledFuture<?> task = pingTask;
            if (task != null) {
                task.cancel(false);
                pingTask = null;
            }
        }

        private void sendPingSafely(WebSocket ws) {
            try {
                ws.sendText(PING_FRAME, true);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Bybit WS ping send failed for account %d", account.getId());
            }
        }

        private void authenticate(WebSocket ws) throws JsonProcessingException {
            String apiKey = cipher.decrypt(account.getApiKeyEncrypted());
            String apiSecret = cipher.decrypt(account.getApiSecretEncrypted());
            long expires = System.currentTimeMillis() + AUTH_EXPIRES_MS;
            String toSign = "GET/realtime" + expires;
            String signed = hmacHex(apiSecret, toSign);
            String authMsg = mapper.writeValueAsString(Map.of(
                    "op", "auth",
                    "args", List.of(apiKey, expires, signed)
            ));
            ws.sendText(authMsg, true);
        }

        private void subscribe(WebSocket ws) throws JsonProcessingException {
            String subMsg = mapper.writeValueAsString(Map.of(
                    "op", "subscribe",
                    "args", List.of(TOPIC_POSITION, TOPIC_EXECUTION, TOPIC_ORDER, TOPIC_WALLET)
            ));
            ws.sendText(subMsg, true);
        }
    }
}
