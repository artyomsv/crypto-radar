package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.lifecycle.OrderPlacer;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.policy.GuardrailPolicy;
import com.cryptoradar.execution.policy.GuardrailPolicy.Decision;
import com.cryptoradar.execution.policy.GuardrailPolicy.SignalCandidate;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to the Redis {@code crypto:signals} channel and drives the
 * intake pipeline:
 * <pre>
 *   pubsub message -> parse envelope -> per-account FlipTracker.observe(...)
 *   -> GuardrailPolicy.evaluate(...) -> OrderPlacer.place(...) or .close(...)
 * </pre>
 * Handles both {@code alert} (single signal) and {@code overview} (signal list)
 * envelope types emitted by signal-service.
 */
@ApplicationScoped
public class SignalSubscriber {

    private static final Logger LOG = Logger.getLogger(SignalSubscriber.class);
    private static final String CHANNEL = "crypto:signals";
    private static final String TYPE_ALERT = "alert";
    private static final String TYPE_OVERVIEW = "overview";
    private static final String LABEL_BUY = "BUY";
    private static final String LABEL_STRONG_BUY = "STRONG_BUY";
    private static final String LABEL_SELL = "SELL";
    private static final String LABEL_STRONG_SELL = "STRONG_SELL";
    private static final String DIRECTION_LONG = "LONG";
    private static final String DIRECTION_SHORT = "SHORT";
    private static final String DEFAULT_STRATEGY = "dimension";
    private static final int CONNECT_DELAY_SECONDS = 3;
    private static final int RECONNECT_DELAY_SECONDS = 10;

    // Shared daemon scheduler — one per JVM. Previous impl created a fresh single-thread
    // pool inside connect()'s error branch, leaking a thread pool per reconnect attempt.
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "signal-subscriber-reconnect");
                t.setDaemon(true);
                return t;
            });

    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @Inject FlipTracker flipTracker;
    @Inject GuardrailPolicy guardrails;
    @Inject OrderPlacer orderPlacer;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    String redisHosts;

    // Vector D — phase-2 data shows alignment 40-55 lost 8.87R across 19 trades.
    // Floor dispatch at 70 to exclude that bucket. Detector-originated alerts
    // lack an alignment field and bypass this filter (see isOverviewEligible).
    @ConfigProperty(name = "execution.alignment.floor", defaultValue = "70")
    int alignmentFloor;

    void onStart(@Observes StartupEvent event) {
        // Defer connection so CDI + DB init finish first; Redis may not be reachable in tests.
        SCHEDULER.schedule(this::connect, CONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    void connect() {
        LOG.info("SignalSubscriber connecting to Redis");
        RedisOptions options = new RedisOptions().setConnectionString(redisHosts);
        Redis.createClient(vertx, options).connect().subscribe().with(
                this::setupSubscription,
                err -> {
                    LOG.errorf(err, "SignalSubscriber Redis connect failed - retry in %ds",
                            RECONNECT_DELAY_SECONDS);
                    SCHEDULER.schedule(this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
                }
        );
    }

    void setupSubscription(RedisConnection conn) {
        LOG.infof("SignalSubscriber subscribed to %s", CHANNEL);
        conn.handler(resp -> {
            if (resp == null || resp.size() < 3) return;
            String kind = resp.get(0).toString();
            if (!"message".equals(kind)) return;
            String payload = resp.get(2).toString();
            // Redis pub/sub callbacks run on the Vert.x event-loop thread; @Transactional
            // requires a worker thread. Dispatch to the blocking pool so onMessage can
            // open a JTA tx cleanly.
            vertx.executeBlocking(() -> {
                try {
                    onMessage(payload);
                } catch (RuntimeException e) {
                    LOG.errorf(e, "message handler error");
                }
                return null;
            }).subscribe().with(
                    v -> {},
                    err -> LOG.errorf(err, "executeBlocking failed for signal message"));
        });
        conn.send(io.vertx.mutiny.redis.client.Request.cmd(io.vertx.mutiny.redis.client.Command.SUBSCRIBE).arg(CHANNEL))
                .subscribe().with(
                        v -> LOG.info("SUBSCRIBE response received"),
                        err -> LOG.errorf(err, "SUBSCRIBE failed"));
    }

    /**
     * Entry point — exposed for unit tests. Accepts the raw JSON payload
     * of a single pub/sub message.
     */
    @Transactional
    public void onMessage(String json) {
        try {
            JsonNode envelope = mapper.readTree(json);
            String type = envelope.path("type").asText("");
            JsonNode data = envelope.path("data");

            if (TYPE_ALERT.equals(type)) {
                // Alerts are one-shot, high-conviction events (detector fires or
                // NEUTRAL→actionable transitions). They bypass FlipTracker: the
                // 2-tick persistence rule exists to filter flappy dimension-scoring
                // overview ticks, which doesn't apply to discrete alerts.
                handleAlert(data.path("signal"));
            } else if (TYPE_OVERVIEW.equals(type)) {
                JsonNode signals = data.path("signals");
                if (signals.isArray()) {
                    signals.forEach(this::handleOverviewSignal);
                }
            }
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "parse error on incoming signal");
        } catch (RuntimeException e) {
            LOG.warnf(e, "unexpected error handling signal message");
        }
    }

    private void handleOverviewSignal(JsonNode signalNode) {
        String symbol = signalNode.path("symbol").asText();
        String label = signalNode.path("signal").asText();
        if (symbol.isEmpty() || label.isEmpty()) return;
        if (!isActionableLabel(label)) return;
        if (isBelowAlignmentFloor(signalNode)) {
            LOG.debugf("ALIGNMENT_FLOOR skip %s %s alignment=%d floor=%d",
                    symbol, label, signalNode.path("alignment").asInt(-1), alignmentFloor);
            return;
        }

        List<ExchangeAccount> accounts = accountRepo.listAll();
        if (accounts.isEmpty()) return;

        for (ExchangeAccount account : accounts) {
            dispatchForAccount(account, signalNode, symbol, label);
        }
    }

    // Vector D gate. Returns true when the overview signal carries an
    // alignment int below the configured floor. Returns false when the
    // field is absent (detector-originated alerts never have it) so this
    // method is safe to call on any node; the caller scopes by envelope.
    boolean isBelowAlignmentFloor(JsonNode signalNode) {
        JsonNode alignmentNode = signalNode.get("alignment");
        if (alignmentNode == null || !alignmentNode.isNumber()) return false;
        return alignmentNode.asInt() < alignmentFloor;
    }

    private void handleAlert(JsonNode signalNode) {
        String symbol = signalNode.path("symbol").asText();
        String label = signalNode.path("signal").asText();
        if (symbol.isEmpty() || label.isEmpty()) return;
        if (!isActionableLabel(label)) return;

        String direction = isLongLabel(label) ? DIRECTION_LONG : DIRECTION_SHORT;
        String opposite = DIRECTION_LONG.equals(direction) ? DIRECTION_SHORT : DIRECTION_LONG;
        List<ExchangeAccount> accounts = accountRepo.listAll();
        if (accounts.isEmpty()) return;

        for (ExchangeAccount account : accounts) {
            // Close any opposite-direction open position first, then enter.
            // Mirror of FlipTracker's CLOSE_*/ENTER_* branches but without the
            // 2-tick persistence gate that applies to overview-ticks only.
            boolean hasOpposite = false;
            for (ExecutedTrade t : tradeRepo.findOpenForAccount(account.getId())) {
                if (symbol.equals(t.getSymbol()) && opposite.equals(t.getDirection())) {
                    hasOpposite = true;
                    break;
                }
            }
            if (hasOpposite) {
                dispatchClose(account, symbol, opposite);
                continue;
            }
            dispatchEnter(account, signalNode, symbol, direction);
        }
    }

    private static boolean isActionableLabel(String label) {
        return LABEL_BUY.equals(label) || LABEL_STRONG_BUY.equals(label)
                || LABEL_SELL.equals(label) || LABEL_STRONG_SELL.equals(label);
    }

    private static boolean isLongLabel(String label) {
        return LABEL_BUY.equals(label) || LABEL_STRONG_BUY.equals(label);
    }

    private void dispatchForAccount(ExchangeAccount account, JsonNode signalNode,
                                     String symbol, String label) {
        // Single DB pass: check if this account holds any open position on this symbol
        // in either direction, across all strategies. Strategy-scoped dedup is a separate
        // check in dispatchEnter via findOpenBySymbolAndDirectionAndStrategy.
        boolean hasLong = false;
        boolean hasShort = false;
        for (ExecutedTrade t : tradeRepo.findOpenForAccount(account.getId())) {
            if (!symbol.equals(t.getSymbol())) continue;
            if (DIRECTION_LONG.equals(t.getDirection())) hasLong = true;
            else if (DIRECTION_SHORT.equals(t.getDirection())) hasShort = true;
            if (hasLong && hasShort) break;
        }

        FlipTracker.Action action = flipTracker.observe(
                symbol, label, account.getFlipPersistenceTicks(), hasLong, hasShort);

        switch (action) {
            case NO_ACTION -> { /* skip */ }
            case ENTER_LONG -> dispatchEnter(account, signalNode, symbol, DIRECTION_LONG);
            case ENTER_SHORT -> dispatchEnter(account, signalNode, symbol, DIRECTION_SHORT);
            case CLOSE_LONG -> dispatchClose(account, symbol, DIRECTION_LONG);
            case CLOSE_SHORT -> dispatchClose(account, symbol, DIRECTION_SHORT);
        }
    }

    private void dispatchEnter(ExchangeAccount account, JsonNode signalNode,
                                String symbol, String direction) {
        SignalCandidate candidate = new SignalCandidate(
                symbol, direction,
                signalNode.path("strategy").asText(DEFAULT_STRATEGY),
                signalNode.path("signalId").asText(null),
                Instant.now());

        int openCount = tradeRepo.countOpenForAccount(account.getId());
        // Phase 1: daily-halt inactive until wallet-based today-pnl tracking ships
        // in a later iteration (see Plan 2b tech-debt).
        BigDecimal todayPnlPct = BigDecimal.ZERO;
        boolean dedupHit = tradeRepo.findOpenBySymbolAndDirectionAndStrategy(
                account.getId(), symbol, direction, candidate.strategy()).isPresent();

        Decision decision = guardrails.evaluate(account, candidate, openCount, todayPnlPct, dedupHit);
        if (!decision.accepted()) {
            logBlock(account, candidate, decision.blockReason());
            return;
        }

        BigDecimal entry = safeBd(signalNode.path("entryPrice").asText(null));
        BigDecimal stop = safeBd(signalNode.path("stopPrice").asText(null));
        BigDecimal target = safeBd(signalNode.path("targetPrice").asText(null));
        if (entry == null || stop == null || target == null) return;

        orderPlacer.place(account, new OrderPlacer.PlacementRequest(
                symbol, direction, candidate.strategy(), candidate.signalId(), entry, stop, target));
    }

    private void dispatchClose(ExchangeAccount account, String symbol, String direction) {
        tradeRepo.findOpenForAccount(account.getId()).stream()
                .filter(t -> symbol.equals(t.getSymbol()) && direction.equals(t.getDirection()))
                .forEach(t -> orderPlacer.close(account, t, ExitReason.FLIP_CLOSE));
    }

    private void logBlock(ExchangeAccount account, SignalCandidate candidate,
                           ExecutionEventType reason) {
        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(reason);
        ev.setSignalId(candidate.signalId());
        ev.setMetadata(Map.of(
                "symbol", candidate.symbol(),
                "direction", candidate.direction()));
        eventRepo.persist(ev);
    }

    private static BigDecimal safeBd(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
