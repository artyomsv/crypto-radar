package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies PR1 stop-placement invariants: the minimum-risk-distance floor
 * prevents LTC-class micro-stops (where ATR collapses and support sits at
 * entry price), and {@code MIN_RR} is at its 2.0 floor rather than the
 * previous 5.0.
 */
class SignalEngineStopPlacementTest {

    private static final double ENTRY_PRICE = 100.0;
    private static final double MIN_RISK_PCT = 0.005;

    private final SignalEngine engine = new SignalEngine();

    @Test
    @DisplayName("min-risk floor widens near-zero stop to 0.5% of entry")
    void enforcesMinRiskDistanceOnBullishSignal() {
        // Construct bullish analytics where support ≈ price and ATR ≈ 0.
        // Prior behaviour: stop = price - atr*1.5 AND min(stop, support - atr*0.5).
        // Both candidates collapse to ~= price, producing a stop fractions of a cent away.
        // New behaviour: widen to price * (1 - MIN_RISK_PCT).
        Map<String, Object> analytics = bullishAnalyticsWithCollapsedStop();
        Map<String, Object> priceData = Map.of("price", ENTRY_PRICE);

        TradingSignal signal = engine.computeSignal(
                "TESTUSDT",
                analytics,
                strongBullishWhale(),
                Map.of(),
                priceData,
                Map.of());

        assertNotNull(signal.getSuggestedStopLoss(), "expected a stop for bullish signal");
        double stop = signal.getSuggestedStopLoss();
        double risk = ENTRY_PRICE - stop;
        double riskPct = risk / ENTRY_PRICE;

        assertTrue(riskPct >= MIN_RISK_PCT - 1e-9,
                "risk must be at least MIN_RISK_PCT; got " + riskPct);
        assertEquals(ENTRY_PRICE * (1 - MIN_RISK_PCT), stop, 0.01,
                "stop should be widened exactly to the floor");
    }

    @Test
    @DisplayName("normal ATR-driven stop unchanged by floor")
    void normalStopNotAffectedByFloor() {
        Map<String, Object> analytics = bullishAnalyticsWithHealthyAtr();
        Map<String, Object> priceData = Map.of("price", ENTRY_PRICE);

        TradingSignal signal = engine.computeSignal(
                "TESTUSDT",
                analytics,
                strongBullishWhale(),
                Map.of(),
                priceData,
                Map.of());

        assertNotNull(signal.getSuggestedStopLoss());
        double riskPct = (ENTRY_PRICE - signal.getSuggestedStopLoss()) / ENTRY_PRICE;
        // With ATR ≈ 2% and multiplier 1.5, unwidened stop sits ~3% below entry.
        assertTrue(riskPct > 0.02, "risk should reflect ATR, not the floor; got " + riskPct);
    }

    @Test
    @DisplayName("MIN_RR floor is 2.0: signals with close resistance take resistance-based target")
    void minRrFloorIsTwoR() {
        // Resistance at 1.5% above entry, support at 1% below.
        // Risk = 1% of entry. Old MIN_RR=5 would force target to 5%.
        // New MIN_RR=2.0 allows resistance-based target (1.5%) via Math.max(minTarget=2%, resistance=1.5%) = 2%.
        // So target is 2% above entry, rr = 2.0.
        Map<String, Object> analytics = bullishAnalyticsWithCloseResistance();
        Map<String, Object> priceData = Map.of("price", ENTRY_PRICE);

        TradingSignal signal = engine.computeSignal(
                "TESTUSDT",
                analytics,
                strongBullishWhale(),
                Map.of(),
                priceData,
                Map.of());

        assertNotNull(signal.getRiskRewardRatio(), "expected RR populated");
        assertTrue(signal.getRiskRewardRatio() >= 2.0 - 0.1,
                "RR should respect MIN_RR=2.0 floor; got " + signal.getRiskRewardRatio());
        assertTrue(signal.getRiskRewardRatio() <= 3.0,
                "RR should not default to the old 5.0 floor; got " + signal.getRiskRewardRatio());
    }

    private Map<String, Object> bullishAnalyticsWithCollapsedStop() {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", 25.0);             // oversold → +80
        indicators.put("macdHistogram", 0.1);      // positive → +30
        indicators.put("sma200", ENTRY_PRICE * 0.9);
        indicators.put("atr14", 0.0001);           // near-zero — triggers micro-stop
        indicators.put("bollingerUpper", ENTRY_PRICE * 1.02);
        indicators.put("bollingerLower", ENTRY_PRICE * 0.98);
        indicators.put("bollingerMiddle", ENTRY_PRICE * 0.985);   // lower third → +20

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);
        analytics.put("supportLevel", ENTRY_PRICE);                // AT price — collapses stop
        analytics.put("resistanceLevel", ENTRY_PRICE * 1.05);
        analytics.put("overallScore", 20.0);
        analytics.put("volumeTrend", "INCREASING");
        return analytics;
    }

    private Map<String, Object> bullishAnalyticsWithHealthyAtr() {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", 25.0);
        indicators.put("macdHistogram", 0.1);
        indicators.put("sma200", ENTRY_PRICE * 0.9);
        indicators.put("atr14", ENTRY_PRICE * 0.02);   // 2% ATR — healthy
        indicators.put("bollingerUpper", ENTRY_PRICE * 1.02);
        indicators.put("bollingerLower", ENTRY_PRICE * 0.98);
        indicators.put("bollingerMiddle", ENTRY_PRICE * 0.985);

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);
        analytics.put("supportLevel", ENTRY_PRICE * 0.97);
        analytics.put("resistanceLevel", ENTRY_PRICE * 1.05);
        analytics.put("overallScore", 20.0);
        analytics.put("volumeTrend", "INCREASING");
        return analytics;
    }

    private Map<String, Object> bullishAnalyticsWithCloseResistance() {
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("rsi14", 25.0);
        indicators.put("macdHistogram", 0.1);
        indicators.put("sma200", ENTRY_PRICE * 0.9);
        indicators.put("atr14", ENTRY_PRICE * 0.01);
        indicators.put("bollingerUpper", ENTRY_PRICE * 1.02);
        indicators.put("bollingerLower", ENTRY_PRICE * 0.98);
        indicators.put("bollingerMiddle", ENTRY_PRICE * 0.985);

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);
        analytics.put("supportLevel", ENTRY_PRICE * 0.99);    // 1% below → risk ≈ 1.5% (stop incl. atr buffer)
        analytics.put("resistanceLevel", ENTRY_PRICE * 1.015); // 1.5% above — close
        analytics.put("overallScore", 20.0);
        analytics.put("volumeTrend", "INCREASING");
        return analytics;
    }

    private Map<String, Object> strongBullishWhale() {
        Map<String, Object> m = new HashMap<>();
        m.put("whalePressure", 60.0);
        m.put("tradeCount1h", 30);
        return m;
    }
}
