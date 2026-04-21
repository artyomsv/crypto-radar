package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
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
    private static final long RECONNECT_DELAY_SECONDS = 2L;

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
    @Inject ExecutionEventRepository eventRepo;
    @Inject ExecutionBroadcaster broadcaster;

    private final HttpClient http = HttpClient.newHttpClient();

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
            LOG.errorf(e, "BybitV5WsClient connectAll failed — no Bybit WS frames will arrive");
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
            t.setStatus(TradeStatus.CLOSED);
            t.setClosedAt(Instant.now());
            t.setLastSyncAt(Instant.now());
            tradeRepo.persist(t);
            eventRepo.persist(makeEvent(
                    account.getId(), ExecutionEventType.POSITION_CLOSED,
                    Map.of(
                            "symbol", symbol,
                            "tradeId", t.getId(),
                            "source", "ws_position"
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

        // First-fill entry tracking: stamp entry price on the very first trade
        // execution so the trade carries the real fill price, not the
        // originally-placed limit. Subsequent partial fills don't overwrite.
        if (trade.getEntryPrice() == null && execPrice != null) {
            trade.setEntryPrice(execPrice);
            trade.setLastSyncAt(Instant.now());
            if (trade.getStatus() == TradeStatus.PENDING_PLACE) {
                trade.setStatus(TradeStatus.OPEN);
            }
            tradeRepo.persist(trade);
        }

        eventRepo.persist(makeEvent(
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
    private class Listener implements WebSocket.Listener {

        private final ExchangeAccount account;
        private final StringBuilder partial = new StringBuilder();

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
                BybitV5WsClient.this.handleMessage(account, msg);
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOG.warnf("Bybit WS closed for account %d — status=%d reason=%s",
                    account.getId(), statusCode, reason);
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
