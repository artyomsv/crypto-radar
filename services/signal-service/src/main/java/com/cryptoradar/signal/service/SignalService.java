package com.cryptoradar.signal.service;

import com.cryptoradar.signal.event.RedisEventPublisher;
import com.cryptoradar.signal.model.SignalOverview;
import com.cryptoradar.signal.model.TradingSignal;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates signal computation for all tracked symbols.
 * Tracks previous signals for transition detection and alert publishing.
 */
@ApplicationScoped
public class SignalService {

    private static final Logger LOG = Logger.getLogger(SignalService.class);

    private static final String STRONG_BUY = "STRONG_BUY";
    private static final String STRONG_SELL = "STRONG_SELL";
    private static final String NEUTRAL = "NEUTRAL";

    private static final Set<String> ACTIONABLE_SIGNALS = Set.of(STRONG_BUY, STRONG_SELL);
    private static final Set<String> NON_ACTIONABLE_SIGNALS = Set.of(NEUTRAL, "BUY", "SELL");

    private final DataAggregator dataAggregator;
    private final SignalEngine signalEngine;
    private final RedisEventPublisher redisPublisher;

    private final ConcurrentHashMap<String, String> previousSignals = new ConcurrentHashMap<>();
    private final AtomicReference<SignalOverview> cachedOverview = new AtomicReference<>();

    public SignalService(DataAggregator dataAggregator,
                         SignalEngine signalEngine,
                         RedisEventPublisher redisPublisher) {
        this.dataAggregator = dataAggregator;
        this.signalEngine = signalEngine;
        this.redisPublisher = redisPublisher;
    }

    @SuppressWarnings("unchecked")
    public SignalOverview computeAllSignals() {
        Map<String, Object> whaleOverview = dataAggregator.fetchWhaleAnalytics();
        Map<String, Object> derivativesOverview = dataAggregator.fetchDerivativesOverview();
        List<Map<String, Object>> prices = dataAggregator.fetchPrices();
        Map<String, Object> marketOverview = dataAggregator.fetchMarketOverview();
        Map<String, Object> macroData = dataAggregator.fetchMacro();

        // Build symbol-indexed maps for whale and derivatives data
        Map<String, Map<String, Object>> whaleBySymbol = indexBySymbol(whaleOverview, "symbolAnalytics");
        Map<String, Map<String, Object>> derivativesBySymbol = indexBySymbol(derivativesOverview, "symbolData");
        Map<String, Map<String, Object>> priceBySymbol = indexPricesbySymbol(prices);

        // Collect all known symbols from prices (primary source of tracked coins)
        List<String> symbols = new ArrayList<>(priceBySymbol.keySet());
        if (symbols.isEmpty()) {
            LOG.warn("No symbols found from price data — skipping signal computation");
            return buildEmptyOverview();
        }

        // Inject Fear & Greed into macroData for sentiment scoring
        Map<String, Object> enrichedMacro = enrichMacroWithFearGreed(macroData, marketOverview);

        List<TradingSignal> signals = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                Map<String, Object> analytics = dataAggregator.fetchAnalytics(symbol);
                Map<String, Object> symbolWhale = whaleBySymbol.get(symbol);
                Map<String, Object> symbolDerivatives = derivativesBySymbol.get(symbol);
                Map<String, Object> symbolPrice = priceBySymbol.get(symbol);

                TradingSignal signal = signalEngine.computeSignal(
                        symbol, analytics, symbolWhale, symbolDerivatives, symbolPrice, enrichedMacro);

                // Set previous signal for transition detection
                String prevSignal = previousSignals.get(symbol);
                signal.setPreviousSignal(prevSignal);

                // Detect transitions to STRONG_BUY or STRONG_SELL
                detectAndPublishAlert(signal, prevSignal);

                // Update previous signal tracking
                previousSignals.put(symbol, signal.getSignal());

                signals.add(signal);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to compute signal for %s", symbol);
            }
        }

        SignalOverview overview = buildOverview(signals);
        cachedOverview.set(overview);
        return overview;
    }

    public SignalOverview getSignalOverview() {
        SignalOverview overview = cachedOverview.get();
        if (overview == null) {
            return buildEmptyOverview();
        }
        return overview;
    }

    public TradingSignal getSignal(String symbol) {
        SignalOverview overview = cachedOverview.get();
        if (overview == null) return null;

        return overview.getSignals().stream()
                .filter(s -> s.getSymbol().equalsIgnoreCase(symbol))
                .findFirst()
                .orElse(null);
    }

    // --- Internal helpers ---

    private void detectAndPublishAlert(TradingSignal signal, String prevSignal) {
        if (!ACTIONABLE_SIGNALS.contains(signal.getSignal())) return;
        if (signal.getSignal().equals(prevSignal)) return;

        // Transition from non-strong to strong signal
        boolean wasNonActionable = prevSignal == null || !ACTIONABLE_SIGNALS.contains(prevSignal);
        if (wasNonActionable) {
            LOG.infof("ALERT: %s transitioned to %s (confidence %d%%)",
                    signal.getSymbol(), signal.getSignal(), signal.getConfidence());
            redisPublisher.publishAlert(signal);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> indexBySymbol(Map<String, Object> overview, String listKey) {
        Map<String, Map<String, Object>> result = new ConcurrentHashMap<>();
        if (overview == null) return result;

        Object listObj = overview.get(listKey);
        if (listObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object symbolObj = map.get("symbol");
                    if (symbolObj instanceof String symbol) {
                        result.put(symbol, (Map<String, Object>) map);
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> indexPricesbySymbol(List<Map<String, Object>> prices) {
        Map<String, Map<String, Object>> result = new ConcurrentHashMap<>();
        if (prices == null) return result;

        for (Map<String, Object> price : prices) {
            Object symbolObj = price.get("symbol");
            if (symbolObj instanceof String symbol) {
                result.put(symbol, price);
            }
        }
        return result;
    }

    private Map<String, Object> enrichMacroWithFearGreed(Map<String, Object> macroData,
                                                          Map<String, Object> marketOverview) {
        Map<String, Object> enriched = new ConcurrentHashMap<>();
        if (macroData != null) {
            enriched.putAll(macroData);
        }
        if (marketOverview != null && marketOverview.containsKey("fearGreedIndex")) {
            enriched.put("fearGreedIndex", marketOverview.get("fearGreedIndex"));
        }
        return enriched;
    }

    private SignalOverview buildOverview(List<TradingSignal> signals) {
        SignalOverview overview = new SignalOverview();
        overview.setTimestamp(Instant.now());

        int strongBuy = 0, buy = 0, neutral = 0, sell = 0, strongSell = 0;
        for (TradingSignal signal : signals) {
            switch (signal.getSignal()) {
                case "STRONG_BUY" -> strongBuy++;
                case "BUY" -> buy++;
                case "NEUTRAL" -> neutral++;
                case "SELL" -> sell++;
                case "STRONG_SELL" -> strongSell++;
            }
        }

        overview.setStrongBuyCount(strongBuy);
        overview.setBuyCount(buy);
        overview.setNeutralCount(neutral);
        overview.setSellCount(sell);
        overview.setStrongSellCount(strongSell);
        overview.setMarketBias(computeMarketBias(strongBuy, buy, sell, strongSell));

        // Sort by absolute score descending
        signals.sort(Comparator.comparingDouble(s -> -Math.abs(s.getOverallScore())));
        overview.setSignals(signals);

        // Top opportunity = highest confidence actionable signal
        signals.stream()
                .filter(s -> !NEUTRAL.equals(s.getSignal()))
                .max(Comparator.comparingInt(TradingSignal::getConfidence))
                .ifPresent(overview::setTopOpportunity);

        return overview;
    }

    private String computeMarketBias(int strongBuy, int buy, int sell, int strongSell) {
        int bullish = strongBuy * 2 + buy;
        int bearish = strongSell * 2 + sell;

        if (bullish > bearish * 2) return "STRONGLY_BULLISH";
        if (bullish > bearish) return "BULLISH";
        if (bearish > bullish * 2) return "STRONGLY_BEARISH";
        if (bearish > bullish) return "BEARISH";
        return "NEUTRAL";
    }

    private SignalOverview buildEmptyOverview() {
        SignalOverview overview = new SignalOverview();
        overview.setTimestamp(Instant.now());
        overview.setMarketBias("UNKNOWN");
        overview.setSignals(List.of());
        return overview;
    }
}
