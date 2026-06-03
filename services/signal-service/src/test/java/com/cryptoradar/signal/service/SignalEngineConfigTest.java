package com.cryptoradar.signal.service;

import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that engine behaviour changes when SignalConfig values change.
 * Each test compares two engines — one with defaults(), one with a single
 * tuned parameter — and asserts the expected scoring shift.
 */
class SignalEngineConfigTest {

    private static final double PRICE = 50000.0;

    // --- RSI threshold tests ---

    @Test
    @DisplayName("tightening RSI overbought threshold from 70→60 causes RSI-65 to score extreme bearish")
    void rsiOverboughtThresholdTightened() {
        // Default: overboughtApproaching=60, overboughtExtreme=70
        // RSI 65 sits between 60 and 70 → "approaching" score (-40) with defaults.
        // After tightening: overboughtApproaching=55, overboughtExtreme=60 → RSI 65 > 60 → extreme (-80).
        SignalConfig.Rsi tightRsi = new SignalConfig.Rsi(30, 40, 55, 60,
                80, 40, -40, -80);
        SignalConfig tight = withRsi(tightRsi);

        double defaultScore = rsiScore(SignalConfig.defaults(), 65.0);
        double tightScore   = rsiScore(tight, 65.0);

        assertTrue(defaultScore > -60.0,
                "defaults: RSI 65 should be 'approaching' (-40), got " + defaultScore);
        assertTrue(tightScore <= -75.0,
                "tight config: RSI 65 should hit extreme (-80), got " + tightScore);
    }

    @Test
    @DisplayName("relaxing RSI oversold threshold from 30→20 makes RSI-25 produce approaching score, not extreme")
    void rsiOversoldThresholdRelaxed() {
        // Default: oversoldExtreme=30 → RSI 25 scores extreme (+80).
        // Relaxed: oversoldExtreme=20, oversoldApproaching=30 → RSI 25 falls in 'approaching' (+40).
        SignalConfig.Rsi relaxedRsi = new SignalConfig.Rsi(20, 30, 60, 70,
                80, 40, -40, -80);
        SignalConfig relaxed = withRsi(relaxedRsi);

        double defaultScore = rsiScore(SignalConfig.defaults(), 25.0);
        double relaxedScore = rsiScore(relaxed, 25.0);

        assertTrue(defaultScore >= 75.0,
                "defaults: RSI 25 should be extreme (+80), got " + defaultScore);
        assertTrue(relaxedScore >= 30.0 && relaxedScore < 60.0,
                "relaxed config: RSI 25 should be approaching (+40), got " + relaxedScore);
    }

    // --- MACD score magnitude test ---

    @Test
    @DisplayName("halving MACD bullish score reduces technical dimension contribution")
    void macdBullishScoreHalved() {
        SignalConfig.Macd halfMacd = new SignalConfig.Macd(15, -30);
        SignalConfig half = withMacd(halfMacd);

        double defaultScore = technicalScore(SignalConfig.defaults(), macdPositiveAnalytics());
        double halfScore    = technicalScore(half, macdPositiveAnalytics());

        assertTrue(defaultScore > halfScore + 5.0,
                "halving MACD score should reduce technical dimension by ~15; got default="
                        + defaultScore + " half=" + halfScore);
    }

    // --- Whale sample-size gate test ---

    @Test
    @DisplayName("raising whale sample threshold damps a low-count pressure reading more aggressively")
    void whaleHigherSampleThresholdIncreasedDampening() {
        // Default minSampleSize=15. With tradeCount=10: dampening = 10/15 = 0.667.
        // Raised to minSampleSize=20: dampening = 10/20 = 0.50 → score dampened further.
        SignalConfig.Whale bigSample = new SignalConfig.Whale(20, 30, 1.20);
        SignalConfig bigSampleConfig = withWhale(bigSample);

        double defaultScore = whaleScore(SignalConfig.defaults(), 80.0, 10);
        double bigSampleScore = whaleScore(bigSampleConfig, 80.0, 10);

        assertTrue(defaultScore > bigSampleScore + 2.0,
                "bigger sample threshold should damp more; default=" + defaultScore
                        + " bigSample=" + bigSampleScore);
    }

    // --- Fear & Greed threshold test ---

    @Test
    @DisplayName("raising extreme-fear ceiling from 10→20 captures more readings as extreme fear")
    void fearGreedExtremeFearCeilingRaised() {
        // Default: extremeFearMax=10. F&G=15 → "mild fear" (+15 scoreModerate).
        // Raised: extremeFearMax=20. F&G=15 → "extreme fear" (+30 scoreExtreme).
        SignalConfig.FearGreed wideFear = new SignalConfig.FearGreed(20, 30, 75, 90, 15, 30);
        SignalConfig wideConfig = withFearGreed(wideFear);

        double defaultScore = fearGreedMacroScore(SignalConfig.defaults(), 15);
        double wideScore    = fearGreedMacroScore(wideConfig, 15);

        assertTrue(defaultScore > 0 && defaultScore < 20.0,
                "defaults: F&G 15 is mild fear (+15); got " + defaultScore);
        assertTrue(wideScore >= 25.0,
                "wide config: F&G 15 should trigger extreme fear (+30); got " + wideScore);
    }

    // --- Helpers ---

    private double rsiScore(SignalConfig config, double rsiValue) {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", rsiValue);
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);

        SignalEngine engine = new SignalEngine(new FakeConfigService(config));
        TradingSignal signal = engine.computeSignal("BTCUSDT", analytics,
                Map.of(), Map.of(), Map.of("price", PRICE), Map.of());

        return dimensionScore(signal, "Technical");
    }

    private double technicalScore(SignalConfig config, Map<String, Object> analytics) {
        SignalEngine engine = new SignalEngine(new FakeConfigService(config));
        TradingSignal signal = engine.computeSignal("BTCUSDT", analytics,
                Map.of(), Map.of(), Map.of("price", PRICE), Map.of());
        return dimensionScore(signal, "Technical");
    }

    private double whaleScore(SignalConfig config, double pressure, int tradeCount) {
        Map<String, Object> whaleData = new HashMap<>();
        whaleData.put("whalePressure", pressure);
        whaleData.put("tradeCount1h", tradeCount);

        SignalEngine engine = new SignalEngine(new FakeConfigService(config));
        TradingSignal signal = engine.computeSignal("BTCUSDT", Map.of(),
                whaleData, Map.of(), Map.of("price", PRICE), Map.of());
        return dimensionScore(signal, "Whale");
    }

    private double fearGreedMacroScore(SignalConfig config, int fearGreedValue) {
        Map<String, Object> macroData = new HashMap<>();
        macroData.put("fearGreedIndex", fearGreedValue);

        SignalEngine engine = new SignalEngine(new FakeConfigService(config));
        TradingSignal signal = engine.computeSignal("BTCUSDT", Map.of(),
                Map.of(), Map.of(), Map.of("price", PRICE), macroData);
        return dimensionScore(signal, "Sentiment");
    }

    private double dimensionScore(TradingSignal signal, String name) {
        return signal.getDimensions().stream()
                .filter(d -> d.name().equals(name))
                .mapToDouble(DimensionScore::score)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Dimension not found: " + name));
    }

    private Map<String, Object> macdPositiveAnalytics() {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", 50.0);          // neutral RSI
        indicators.put("macdHistogram", 0.5);   // positive MACD
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);
        return analytics;
    }

    // --- Config override factories ---

    private static SignalConfig withRsi(SignalConfig.Rsi rsi) {
        SignalConfig d = SignalConfig.defaults();
        return new SignalConfig(d.weights(), d.tradeLevels(), rsi, d.macd(), d.sma200(),
                d.bollinger(), d.volumeConfirmation(), d.supportResistance(), d.whale(),
                d.derivativesFunding(), d.longShortRatio(), d.fearGreed(), d.newsSentiment(),
                d.orderBook(), d.macroBtcDominance(), d.alignment(), d.signalLabels(), d.trail());
    }

    private static SignalConfig withMacd(SignalConfig.Macd macd) {
        SignalConfig d = SignalConfig.defaults();
        return new SignalConfig(d.weights(), d.tradeLevels(), d.rsi(), macd, d.sma200(),
                d.bollinger(), d.volumeConfirmation(), d.supportResistance(), d.whale(),
                d.derivativesFunding(), d.longShortRatio(), d.fearGreed(), d.newsSentiment(),
                d.orderBook(), d.macroBtcDominance(), d.alignment(), d.signalLabels(), d.trail());
    }

    private static SignalConfig withWhale(SignalConfig.Whale whale) {
        SignalConfig d = SignalConfig.defaults();
        return new SignalConfig(d.weights(), d.tradeLevels(), d.rsi(), d.macd(), d.sma200(),
                d.bollinger(), d.volumeConfirmation(), d.supportResistance(), whale,
                d.derivativesFunding(), d.longShortRatio(), d.fearGreed(), d.newsSentiment(),
                d.orderBook(), d.macroBtcDominance(), d.alignment(), d.signalLabels(), d.trail());
    }

    private static SignalConfig withFearGreed(SignalConfig.FearGreed fg) {
        SignalConfig d = SignalConfig.defaults();
        return new SignalConfig(d.weights(), d.tradeLevels(), d.rsi(), d.macd(), d.sma200(),
                d.bollinger(), d.volumeConfirmation(), d.supportResistance(), d.whale(),
                d.derivativesFunding(), d.longShortRatio(), fg, d.newsSentiment(),
                d.orderBook(), d.macroBtcDominance(), d.alignment(), d.signalLabels(), d.trail());
    }
}
