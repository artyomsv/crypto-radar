package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR1 regression guard for LS detector: when ATR collapses and the swing-low
 * wick sits near the entry price (the LTC pattern that contaminated 46 of 54
 * LS trades), the stop must be widened to {@code LS_MIN_RISK_PCT} of entry
 * rather than sitting fractions of a percent away.
 */
class LiquiditySweepDetectorTest {

    private static final double MIN_RISK_PCT = 0.005;

    private final LiquiditySweepDetector detector = new LiquiditySweepDetector();

    @Test
    @DisplayName("near-zero ATR with swing low at entry widens stop to MIN_RISK_PCT floor")
    void tinyAtrStopWidenedToFloor() {
        double entry = 100.0;
        // Sweep setup where swingLow ≈ entry and ATR is tiny.
        // Without the guard, stop = swingLow - 0.5*atr ≈ entry → risk ≈ 0.
        CandleBar trigger = buildBullishSweepTrigger(
                /* open */ 99.995,
                /* high */ 100.01,
                /* low  */ 99.98,
                /* close */ 100.0);
        MarketContext ctx = buildContext(entry, trigger, 99.99, 0.001);

        Optional<TradeSetup> result = detector.detect(ctx);

        assertTrue(result.isPresent(), "detector should fire on this sweep pattern");
        TradeSetup setup = result.get();
        double risk = setup.entryPrice() - setup.stopPrice();
        double riskPct = risk / setup.entryPrice();
        assertTrue(riskPct >= MIN_RISK_PCT - 1e-9,
                "risk must be at least LS_MIN_RISK_PCT; got " + riskPct);
        assertEquals(entry * (1 - MIN_RISK_PCT), setup.stopPrice(), 1e-6,
                "stop should be widened exactly to the floor");
    }

    @Test
    @DisplayName("normal ATR case leaves stop untouched by floor")
    void normalCaseStopUnchangedByFloor() {
        double entry = 100.0;
        CandleBar trigger = buildBullishSweepTrigger(99.0, 101.0, 97.0, 100.5);
        MarketContext ctx = buildContext(entry, trigger, 98.0, 2.0);  // atr=2.0

        Optional<TradeSetup> result = detector.detect(ctx);

        assertTrue(result.isPresent());
        double riskPct = (result.get().entryPrice() - result.get().stopPrice()) / result.get().entryPrice();
        // ATR-derived stop = 97 - 0.5*2 = 96 → risk ≈ 4%. Well above the 0.5% floor.
        assertTrue(riskPct > 0.03,
                "risk should reflect ATR buffer, not the floor; got " + riskPct);
    }

    // --- Fixtures ---

    /**
     * Builds a green trigger bar with a lower wick large enough to pass the
     * wick-to-body ratio requirement (≥ 0.5).
     */
    private CandleBar buildBullishSweepTrigger(double open, double high, double low, double close) {
        return new CandleBar(Instant.now().minusSeconds(3600), open, high, low, close);
    }

    /**
     * Builds an 8-bar 4h series where the first 7 bars set a swing low at
     * {@code swingLow}, and the 8th is a dummy current bar. The 7th (second-to-
     * last) is the {@code trigger} passed in.
     */
    private MarketContext buildContext(double entry, CandleBar trigger,
                                       double swingLow, double atr) {
        List<CandleBar> bars = new ArrayList<>();
        // 6 swing bars above swingLow establishing it as the pivot
        for (int i = 0; i < 6; i++) {
            bars.add(new CandleBar(
                    Instant.now().minusSeconds((10 - i) * 14400L),
                    entry * 1.01, entry * 1.02, swingLow, entry * 1.015));
        }
        bars.add(trigger);  // 7th bar = trigger
        bars.add(new CandleBar(  // 8th (live) bar
                Instant.now(), entry, entry * 1.001, entry * 0.999, entry));

        Map<String, Object> indicators = new HashMap<>();
        indicators.put("atr14", atr);
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);

        Map<String, Double> dimensionScores = new HashMap<>();
        dimensionScores.put("Derivatives", 0.0);   // pass confluence check

        return new MarketContext(
                "TESTUSDT", entry, analytics, Map.of(), Map.of(), Map.of(),
                dimensionScores, bars);
    }
}
