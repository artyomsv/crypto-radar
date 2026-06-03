package com.cryptoradar.signal.service;

import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G.1 regression guards. Derivatives-service's Bybit long/short feed returns
 * {@code longPct} as a fraction in [0, 1] (e.g. 0.74 = 74% long), despite the
 * misleading field name. Prior to the fix the scorer compared it as a percent
 * in [0, 100], so every observation fell under the "< 30" branch and added
 * +35 contrarian-bullish — regardless of the real positioning. That single
 * bug inverted the derivatives signal on every symbol.
 *
 * <p>These tests pin both forms (fraction from production; percent from
 * historical tests) to produce the same score, so future contract drift
 * shows up immediately.
 */
class SignalEngineDerivativesUnitTest {

    private static final double PRICE = 50000.0;

    private final SignalEngine engine = new SignalEngine(new FakeConfigService(SignalConfig.defaults()));

    @Test
    @DisplayName("crowded longs as fraction (0.74) score bearish, not bullish")
    void crowdedLongsFractionScoresBearish() {
        TradingSignal signal = engine.computeSignal(
                "LTCUSDT",
                Map.of(),
                Map.of(),
                derivativesWithLongPct(0.7443),
                Map.of("price", PRICE),
                Map.of());

        double derivScore = dimensionScore(signal, "Derivatives");
        assertTrue(derivScore <= -30.0,
                "0.7443 longPct (74.43%) must score bearish, got " + derivScore);
    }

    @Test
    @DisplayName("balanced fraction (0.40) does not add long/short contribution")
    void balancedFractionIsNeutral() {
        TradingSignal signal = engine.computeSignal(
                "BTCUSDT",
                Map.of(),
                Map.of(),
                derivativesWithLongPct(0.40),
                Map.of("price", PRICE),
                Map.of());

        double derivScore = dimensionScore(signal, "Derivatives");
        assertTrue(Math.abs(derivScore) <= 5.0,
                "40% long is in the neutral band; got " + derivScore);
    }

    @Test
    @DisplayName("percent form (72.0) still scored as 72% long → bearish (tolerant)")
    void legacyPercentFormStillWorks() {
        TradingSignal signal = engine.computeSignal(
                "LTCUSDT",
                Map.of(),
                Map.of(),
                derivativesWithLongPct(72.0),
                Map.of("price", PRICE),
                Map.of());

        double derivScore = dimensionScore(signal, "Derivatives");
        assertTrue(derivScore <= -30.0,
                "72.0 percent-form still classified crowded-long; got " + derivScore);
    }

    @Test
    @DisplayName("fraction 0.74 and percent 74.0 produce identical derivatives score")
    void fractionAndPercentProduceEqualScore() {
        TradingSignal fractionSignal = engine.computeSignal(
                "LTCUSDT", Map.of(), Map.of(),
                derivativesWithLongPct(0.74),
                Map.of("price", PRICE), Map.of());
        TradingSignal percentSignal = engine.computeSignal(
                "LTCUSDT", Map.of(), Map.of(),
                derivativesWithLongPct(74.0),
                Map.of("price", PRICE), Map.of());
        assertEquals(
                dimensionScore(fractionSignal, "Derivatives"),
                dimensionScore(percentSignal, "Derivatives"),
                0.001,
                "unit normalisation must make fraction and percent equivalent");
    }

    @Test
    @DisplayName("crowded shorts as fraction (0.20) score bullish +35")
    void crowdedShortsFractionScoresBullish() {
        TradingSignal signal = engine.computeSignal(
                "SOLUSDT",
                Map.of(),
                Map.of(),
                derivativesWithLongPct(0.20),
                Map.of("price", PRICE),
                Map.of());

        double derivScore = dimensionScore(signal, "Derivatives");
        assertTrue(derivScore >= 30.0,
                "20% long (80% short) is crowded shorts, contrarian bullish; got " + derivScore);
    }

    private Map<String, Object> derivativesWithLongPct(double value) {
        Map<String, Object> m = new HashMap<>();
        m.put("longPct", value);
        return m;
    }

    private double dimensionScore(TradingSignal signal, String name) {
        return signal.getDimensions().stream()
                .filter(d -> d.name().equals(name))
                .map(DimensionScore::score)
                .findFirst()
                .orElseThrow();
    }
}
