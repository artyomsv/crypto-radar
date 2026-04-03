package com.cryptoradar.derivatives.scheduler;

import com.cryptoradar.derivatives.client.BinanceFuturesClient;
import com.cryptoradar.derivatives.event.RedisEventPublisher;
import com.cryptoradar.derivatives.model.DerivativesOverview;
import com.cryptoradar.derivatives.model.Liquidation;
import com.cryptoradar.derivatives.model.LiquidationMap;
import com.cryptoradar.derivatives.service.DerivativesService;
import com.cryptoradar.derivatives.service.LiquidationMapService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class DerivativesScheduler {

    private static final Logger LOG = Logger.getLogger(DerivativesScheduler.class);

    private static final String LIQUIDATION_WS_URL = "wss://fstream.binance.com/ws/!forceOrder@arr";
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 5_000;

    @Inject
    DerivativesService derivativesService;

    @Inject
    BinanceFuturesClient futuresClient;

    @Inject
    RedisEventPublisher redisPublisher;

    @Inject
    LiquidationMapService liquidationMapService;

    @Inject
    ObjectMapper objectMapper;

    private final AtomicBoolean wsConnected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private volatile WebSocket liquidationWebSocket;

    void onStartup(@Observes StartupEvent event) {
        LOG.info("Derivatives scheduler starting — Binance liquidation WebSocket in 5s...");
        LOG.info("OKX + Bybit liquidation streams managed by their own providers");
        Executors.newSingleThreadScheduledExecutor()
                .schedule(this::connectLiquidationWebSocket, 5, TimeUnit.SECONDS);
        // Note: Binance allForceOrders REST endpoint is deprecated, no historical backfill available.
        // Liquidation data accumulates from 3 live WebSocket streams: Binance, OKX, Bybit.
    }

    @Scheduled(every = "{scheduler.funding.interval}", identity = "funding-rates")
    void fetchFundingRates() {
        try {
            derivativesService.refreshFundingRates();
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled funding rate fetch failed");
        }
    }

    @Scheduled(every = "{scheduler.openinterest.interval}", identity = "open-interest")
    void fetchOpenInterest() {
        try {
            derivativesService.refreshOpenInterest();
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled open interest fetch failed");
        }
    }

    @Scheduled(every = "{scheduler.longshort.interval}", identity = "long-short-ratio")
    void fetchLongShortRatios() {
        try {
            derivativesService.refreshLongShortRatios();
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled long/short ratio fetch failed");
        }
    }

    @Scheduled(every = "15m", identity = "liquidation-maps")
    void computeLiquidationMaps() {
        try {
            Set<String> symbols = futuresClient.getTrackedSymbols();
            for (String symbol : symbols) {
                liquidationMapService.computeAndCache(symbol);
            }
            LOG.infof("Pre-computed liquidation maps for %d symbols", symbols.size());
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled liquidation map computation failed");
        }
    }

    @Scheduled(every = "60s", identity = "derivatives-overview")
    void computeAndPublishOverview() {
        try {
            DerivativesOverview overview = derivativesService.computeOverview();
            redisPublisher.publishOverview(overview);
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled overview computation failed");
        }
    }

    private void backfillLiquidations() {
        LOG.info("Backfilling historical liquidations from Binance (last 7 days)...");
        Set<String> symbols = futuresClient.getTrackedSymbols();
        int total = 0;
        for (String symbol : symbols) {
            try {
                List<Liquidation> liqs = futuresClient.fetchHistoricalLiquidations(symbol);
                for (Liquidation liq : liqs) {
                    derivativesService.storeLiquidationQuietly(liq);
                }
                total += liqs.size();
                LOG.infof("Backfilled %d liquidations for %s", liqs.size(), symbol);
            } catch (Exception e) {
                LOG.warnf("Failed to backfill liquidations for %s: %s", symbol, e.getMessage());
            }
        }
        LOG.infof("Liquidation backfill complete: %d total events", total);
    }

    private void connectLiquidationWebSocket() {
        if (wsConnected.get()) return;

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            Set<String> tracked = futuresClient.getTrackedSymbols();

            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(LIQUIDATION_WS_URL), new WebSocket.Listener() {

                        private final StringBuilder buffer = new StringBuilder();

                        @Override
                        public void onOpen(WebSocket webSocket) {
                            wsConnected.set(true);
                            reconnectAttempts.set(0);
                            liquidationWebSocket = webSocket;
                            LOG.info("Liquidation WebSocket connected");
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                processLiquidationMessage(buffer.toString(), tracked);
                                buffer.setLength(0);
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            wsConnected.set(false);
                            LOG.warnf("Liquidation WebSocket closed: %d %s", statusCode, reason);
                            scheduleReconnect();
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            wsConnected.set(false);
                            LOG.errorf(error, "Liquidation WebSocket error");
                            scheduleReconnect();
                        }
                    });

        } catch (Exception e) {
            LOG.errorf(e, "Failed to connect liquidation WebSocket");
            scheduleReconnect();
        }
    }

    private void processLiquidationMessage(String message, Set<String> tracked) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode order = root.get("o");
            if (order == null) return;

            String symbol = order.get("s").asText();
            if (!tracked.contains(symbol)) return;

            String side = order.get("S").asText();
            double price = order.get("p").asDouble();
            double quantity = order.get("q").asDouble();
            double valueUsd = price * quantity;
            long timeMs = order.get("T").asLong();

            Liquidation liq = new Liquidation(symbol, side, price, quantity, valueUsd,
                    Instant.ofEpochMilli(timeMs));
            derivativesService.recordLiquidation(liq);

            LOG.debugf("Liquidation: %s %s %.2f @ %.2f ($%.0f)",
                    symbol, side, quantity, price, valueUsd);

        } catch (Exception e) {
            LOG.warnf("Failed to parse liquidation message: %s", e.getMessage());
        }
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            LOG.errorf("Max WebSocket reconnect attempts (%d) reached. Giving up.", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        // Exponential backoff: 5s, 10s, 20s, 40s...
        long delayMs = INITIAL_RECONNECT_DELAY_MS * (1L << (attempt - 1));
        long cappedDelay = Math.min(delayMs, 300_000); // max 5 minutes
        LOG.infof("Scheduling WebSocket reconnect attempt %d/%d in %dms",
                attempt, MAX_RECONNECT_ATTEMPTS, cappedDelay);

        Executors.newSingleThreadScheduledExecutor()
                .schedule(this::connectLiquidationWebSocket, cappedDelay, TimeUnit.MILLISECONDS);
    }
}
