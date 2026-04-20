package com.cryptoradar.signal.service;

import com.cryptoradar.signal.event.RedisEventPublisher;
import com.cryptoradar.signal.model.SignalOverview;
import com.cryptoradar.signal.model.SymbolRawData;
import com.cryptoradar.signal.model.TradeSetup;
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

    private final DataAggregator dataAggregator;
    private final SignalEngine signalEngine;
    private final RedisEventPublisher redisPublisher;
    private final GeminiAnalysisService geminiService;
    private final OutcomeTracker outcomeTracker;
    private final TradeSetupEngine tradeSetupEngine;
    private final MarketRegimeService marketRegimeService;

    private final ConcurrentHashMap<String, String> previousSignals = new ConcurrentHashMap<>();
    private final AtomicReference<SignalOverview> cachedOverview = new AtomicReference<>();

    public SignalService(DataAggregator dataAggregator,
                         SignalEngine signalEngine,
                         RedisEventPublisher redisPublisher,
                         GeminiAnalysisService geminiService,
                         OutcomeTracker outcomeTracker,
                         TradeSetupEngine tradeSetupEngine,
                         MarketRegimeService marketRegimeService) {
        this.dataAggregator = dataAggregator;
        this.signalEngine = signalEngine;
        this.redisPublisher = redisPublisher;
        this.geminiService = geminiService;
        this.outcomeTracker = outcomeTracker;
        this.tradeSetupEngine = tradeSetupEngine;
        this.marketRegimeService = marketRegimeService;
    }

    @SuppressWarnings("unchecked")
    public SignalOverview computeAllSignals() {
        Map<String, Object> whaleOverview = dataAggregator.fetchWhaleAnalytics();
        Map<String, Object> derivativesOverview = dataAggregator.fetchDerivativesOverview();
        List<Map<String, Object>> prices = dataAggregator.fetchPrices();
        Map<String, Object> marketOverview = dataAggregator.fetchMarketOverview();
        Map<String, Object> macroData = dataAggregator.fetchMacro();

        Map<String, Map<String, Object>> whaleBySymbol = indexBySymbol(whaleOverview, "symbolAnalytics");
        Map<String, Map<String, Object>> derivativesBySymbol = indexBySymbol(derivativesOverview, "symbolData");
        Map<String, Map<String, Object>> priceBySymbol = indexPricesbySymbol(prices);

        List<String> symbols = new ArrayList<>(priceBySymbol.keySet());
        if (symbols.isEmpty()) {
            LOG.warn("No symbols found from price data — skipping signal computation");
            return buildEmptyOverview();
        }

        Map<String, Object> enrichedMacro = enrichMacroWithFearGreed(macroData, marketOverview);

        // Query regime once per cycle — it refreshes on a 15-minute cadence, so
        // reading it per-symbol would be both wasteful and non-atomic across the
        // same compute pass.
        com.cryptoradar.signal.model.MarketRegime regime = marketRegimeService.currentRegime();

        List<TradingSignal> signals = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                Map<String, Object> analytics = dataAggregator.fetchAnalytics(symbol);
                Map<String, Object> symbolWhale = whaleBySymbol.get(symbol);
                Map<String, Object> symbolDerivatives = derivativesBySymbol.get(symbol);
                Map<String, Object> symbolPrice = priceBySymbol.get(symbol);

                TradingSignal signal = signalEngine.computeSignal(
                        symbol, analytics, symbolWhale, symbolDerivatives, symbolPrice, enrichedMacro, regime);

                String prevSignal = previousSignals.get(symbol);
                signal.setPreviousSignal(prevSignal);

                detectAndPublishAlert(signal, prevSignal);

                // Trigger AI analysis on noteworthy events
                if (geminiService.isEnabled() && isNoteworthyEvent(signal, prevSignal)) {
                    Map<String, Object> rawData = getRawSignalData(symbol);
                    geminiService.triggerAnalysis(symbol, rawData);
                }

                // Attach cached AI analysis if available
                String aiAnalysis = geminiService.getCachedAnalysis(symbol);
                if (aiAnalysis != null) {
                    signal.setAiAnalysis(aiAnalysis);
                    signal.setAiAnalysisTimestamp(geminiService.getCachedAnalysisTimestamp(symbol));
                }

                // Persist actionable transitions so the evaluator can measure them.
                if (isNoteworthyEvent(signal, prevSignal)) {
                    outcomeTracker.trackTransition(signal);
                }

                // Run detector-based trade setups alongside dimension scoring.
                // Each fired setup becomes its own tracked outcome keyed by strategy,
                // so dimension-scoring and every detector are measured independently.
                SymbolRawData rawData = new SymbolRawData(
                        analytics, symbolWhale, symbolDerivatives, symbolPrice, enrichedMacro);
                List<TradeSetup> setups = tradeSetupEngine.detectForSymbol(
                        symbol, rawData, signal.getDimensions());
                for (TradeSetup setup : setups) {
                    outcomeTracker.trackSetup(setup);
                }

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

    /**
     * Returns all raw data that feeds into signal computation for a given symbol.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRawSignalData(String symbol) {
        Map<String, Object> whaleOverview = dataAggregator.fetchWhaleAnalytics();
        Map<String, Object> derivativesOverview = dataAggregator.fetchDerivativesOverview();
        List<Map<String, Object>> prices = dataAggregator.fetchPrices();
        Map<String, Object> marketOverview = dataAggregator.fetchMarketOverview();
        Map<String, Object> macroData = dataAggregator.fetchMacro();
        Map<String, Object> analytics = dataAggregator.fetchAnalytics(symbol);

        Map<String, Map<String, Object>> whaleBySymbol = indexBySymbol(whaleOverview, "symbolAnalytics");
        Map<String, Map<String, Object>> derivativesBySymbol = indexBySymbol(derivativesOverview, "symbolData");
        Map<String, Map<String, Object>> priceBySymbol = indexPricesbySymbol(prices);

        Map<String, Object> enrichedMacro = enrichMacroWithFearGreed(macroData, marketOverview);

        Map<String, Object> rawData = new java.util.LinkedHashMap<>();
        rawData.put("symbol", symbol);
        rawData.put("timestamp", Instant.now().toString());
        rawData.put("analytics", analytics);
        rawData.put("whaleData", whaleBySymbol.get(symbol));
        rawData.put("derivativesData", derivativesBySymbol.get(symbol));
        rawData.put("priceData", priceBySymbol.get(symbol));
        rawData.put("macroData", enrichedMacro);

        TradingSignal signal = getSignal(symbol);
        if (signal != null) {
            Map<String, Object> signalSummary = new java.util.LinkedHashMap<>();
            signalSummary.put("signal", signal.getSignal());
            signalSummary.put("overallScore", signal.getOverallScore());
            signalSummary.put("alignment", signal.getAlignment());
            signalSummary.put("dimensions", signal.getDimensions());
            rawData.put("computedSignal", signalSummary);
        }

        return rawData;
    }

    /**
     * On-demand AI analysis — called from REST endpoint.
     */
    public String requestAiAnalysis(String symbol) {
        Map<String, Object> rawData = getRawSignalData(symbol);
        return geminiService.analyzeNow(symbol, rawData);
    }

    // --- Internal helpers ---

    /**
     * Triggers AI analysis only when a potential trade appears:
     * signal just transitioned to BUY, SELL, STRONG_BUY, or STRONG_SELL.
     */
    private boolean isNoteworthyEvent(TradingSignal signal, String prevSignal) {
        String current = signal.getSignal();
        boolean isActionable = !NEUTRAL.equals(current);
        boolean justTransitioned = prevSignal == null || !prevSignal.equals(current);
        return isActionable && justTransitioned;
    }

    private void detectAndPublishAlert(TradingSignal signal, String prevSignal) {
        if (!ACTIONABLE_SIGNALS.contains(signal.getSignal())) return;
        if (signal.getSignal().equals(prevSignal)) return;

        boolean wasNonActionable = prevSignal == null || !ACTIONABLE_SIGNALS.contains(prevSignal);
        if (wasNonActionable) {
            LOG.infof("ALERT: %s transitioned to %s (alignment %d%%)",
                    signal.getSymbol(), signal.getSignal(), signal.getAlignment());
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

        signals.sort(Comparator.comparingDouble(s -> -Math.abs(s.getOverallScore())));
        overview.setSignals(signals);

        signals.stream()
                .filter(s -> !NEUTRAL.equals(s.getSignal()))
                .max(Comparator.comparingInt(TradingSignal::getAlignment))
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
