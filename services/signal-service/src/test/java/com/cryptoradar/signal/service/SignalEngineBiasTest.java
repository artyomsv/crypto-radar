package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR3 regression guards. After bias removal (stablecoin cap + low-liquidation
 * bonus + BTC-dominance symmetry), the engine must be able to produce SELL
 * and STRONG_SELL signals under realistic bearish inputs — and absolute-
 * threshold dimensions must not silently tilt every signal upward.
 *
 * <p>These are "scoring headroom" checks: failure means the bullish bias has
 * returned and SELL signals will be suppressed in production as they were
 * during the observed 21-day window (0 SHORT outcomes out of 211 fires).
 */
class SignalEngineBiasTest {

    private static final double PRICE = 50000.0;

    private final SignalEngine engine = new SignalEngine();

    @Test
    @DisplayName("bearish inputs produce SELL signal (SELL threshold headroom)")
    void bearishInputsProduceSellSignal() {
        TradingSignal signal = engine.computeSignal(
                "XRPUSDT",
                bearishAnalytics(),
                bearishWhale(),
                bearishDerivatives(),
                Map.of("price", PRICE),
                bearishMacroForAlts());

        assertTrue(signal.getOverallScore() <= -15.0,
                "expected overall_score ≤ -15, got " + signal.getOverallScore());
        assertTrue(signal.getSignal().equals("SELL") || signal.getSignal().equals("STRONG_SELL"),
                "expected SELL/STRONG_SELL under bearish inputs, got " + signal.getSignal());
    }

    @Test
    @DisplayName("strongly bearish inputs produce STRONG_SELL (STRONG_SELL threshold headroom)")
    void stronglyBearishInputsProduceStrongSell() {
        TradingSignal signal = engine.computeSignal(
                "XRPUSDT",
                bearishAnalytics(),
                stronglyBearishWhale(),
                stronglyBearishDerivatives(),
                Map.of("price", PRICE),
                stronglyBearishMacroForAlts());

        assertTrue(signal.getOverallScore() <= -40.0,
                "expected overall_score ≤ -40, got " + signal.getOverallScore());
        assertTrue(signal.getConfidence() >= 60,
                "expected confidence ≥ 60 for STRONG_SELL, got " + signal.getConfidence());
        assertTrue("STRONG_SELL".equals(signal.getSignal()),
                "expected STRONG_SELL, got " + signal.getSignal());
    }

    @Test
    @DisplayName("high stablecoin cap alone no longer tilts score positive")
    void highStablecoinCapDoesNotBiasMacro() {
        // Neutral everything except high stablecoin cap. Was +20 macro → 0 now.
        TradingSignal signal = engine.computeSignal(
                "XRPUSDT",
                neutralAnalytics(),
                neutralWhale(),
                neutralDerivatives(),
                Map.of("price", PRICE),
                Map.of("totalStablecoinCap", 200_000_000_000.0, "btcDominance", 60.0));

        // With btcDominance=60 and symbol=XRP, macro score = -20 (bearish for alts).
        // Old code would have added +20 for stablecoin = net 0 on macro.
        // New code leaves stablecoin informational → macro stays at -20.
        double macroScore = signal.getDimensions().stream()
                .filter(d -> d.name().equals("Macro"))
                .findFirst()
                .orElseThrow()
                .score();
        assertTrue(macroScore <= -15.0,
                "macro should remain bearish for alt at high BTC-dominance; got " + macroScore);
    }

    @Test
    @DisplayName("low liquidation activity no longer awards +10 orderbook bonus")
    void lowLiquidationDoesNotBoostOrderBook() {
        Map<String, Object> derivatives = new HashMap<>();
        derivatives.put("liquidations24hUsd", 1_000.0);       // near-zero
        derivatives.put("openInterestUsd", 1_000_000_000.0);  // big OI → ratio ≈ 1e-6, well under 0.01

        TradingSignal signal = engine.computeSignal(
                "XRPUSDT",
                neutralAnalytics(),
                neutralWhale(),
                derivatives,
                Map.of("price", PRICE),
                neutralMacro());

        double orderBookScore = signal.getDimensions().stream()
                .filter(d -> d.name().equals("Order Book"))
                .findFirst()
                .orElseThrow()
                .score();
        assertTrue(orderBookScore <= 0.0,
                "low-liquidation +10 bonus must be removed; orderBookScore=" + orderBookScore);
    }

    @Test
    @DisplayName("BTC at dominance < 50 gets bearish macro score (was 0 before fix)")
    void btcAtLowDominanceIsBearish() {
        TradingSignal signal = engine.computeSignal(
                "BTCUSDT",
                neutralAnalytics(),
                neutralWhale(),
                neutralDerivatives(),
                Map.of("price", PRICE),
                Map.of("btcDominance", 45.0, "totalStablecoinCap", 200_000_000_000.0));

        double macroScore = signal.getDimensions().stream()
                .filter(d -> d.name().equals("Macro"))
                .findFirst()
                .orElseThrow()
                .score();
        assertTrue(macroScore <= -5.0,
                "BTC with btcDominance < 50 should be mildly bearish; got " + macroScore);
    }

    // --- Fixtures ---

    private Map<String, Object> bearishAnalytics() {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", 78.0);                          // overbought → -80
        indicators.put("macdHistogram", -0.5);                  // negative → -30
        indicators.put("sma200", PRICE * 1.1);                  // price below SMA200 → -35
        indicators.put("atr14", PRICE * 0.02);
        indicators.put("bollingerUpper", PRICE * 1.01);
        indicators.put("bollingerLower", PRICE * 0.95);
        indicators.put("bollingerMiddle", PRICE * 1.005);       // upper third → -20

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);
        analytics.put("supportLevel", PRICE * 0.92);
        analytics.put("resistanceLevel", PRICE * 1.01);
        analytics.put("overallScore", 85.0);                     // near resistance → -30
        analytics.put("volumeTrend", "DECREASING");
        analytics.put("sentimentScore", -0.5);
        return analytics;
    }

    private Map<String, Object> neutralAnalytics() {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", 50.0);
        indicators.put("macdHistogram", null);
        indicators.put("atr14", PRICE * 0.02);
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);
        return analytics;
    }

    private Map<String, Object> bearishWhale() {
        Map<String, Object> m = new HashMap<>();
        m.put("whalePressure", -50.0);
        m.put("tradeCount1h", 30);
        return m;
    }

    private Map<String, Object> stronglyBearishWhale() {
        Map<String, Object> m = new HashMap<>();
        m.put("whalePressure", -90.0);
        m.put("tradeCount1h", 40);
        return m;
    }

    private Map<String, Object> neutralWhale() {
        return Map.of();
    }

    private Map<String, Object> bearishDerivatives() {
        Map<String, Object> m = new HashMap<>();
        m.put("fundingRate", 0.0020);    // overcrowded longs → -50
        m.put("longPct", 72.0);          // crowded long → -35
        m.put("liquidations24hUsd", 1_500_000.0);
        m.put("openInterestUsd", 20_000_000.0);  // ratio 0.075 → high → -20
        return m;
    }

    private Map<String, Object> stronglyBearishDerivatives() {
        Map<String, Object> m = new HashMap<>();
        m.put("fundingRate", 0.0025);
        m.put("longPct", 78.0);
        m.put("liquidations24hUsd", 2_500_000.0);
        m.put("openInterestUsd", 20_000_000.0);
        return m;
    }

    private Map<String, Object> neutralDerivatives() {
        return Map.of();
    }

    private Map<String, Object> bearishMacroForAlts() {
        Map<String, Object> m = new HashMap<>();
        m.put("btcDominance", 58.0);   // alt + high dom → -20
        m.put("fearGreedIndex", 88);   // extreme greed → -30 (contrarian)
        return m;
    }

    private Map<String, Object> stronglyBearishMacroForAlts() {
        Map<String, Object> m = new HashMap<>();
        m.put("btcDominance", 65.0);
        m.put("fearGreedIndex", 95);
        return m;
    }

    private Map<String, Object> neutralMacro() {
        return Map.of();
    }
}
