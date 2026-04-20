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
    @DisplayName("stop widened to MIN_RISK_PCT when swing-low sits close to entry")
    void stopWidenedToFloor() {
        // Sweep where swingLow sits essentially at entry. ATR is tuned just above
        // the regime guard (0.003) so filters pass, but the resulting ATR-derived
        // stop is closer than 0.5% to entry → MIN_RISK_PCT should widen it.
        double entry = 100.0;
        CandleBar trigger = buildBullishSweepTrigger(
                /* open  */ 99.95,
                /* high  */ 100.03,
                /* low   */ 99.80,
                /* close */ 100.0);
        MarketContext ctx = buildContext(entry, trigger, /* swingLow */ 99.95, /* atr */ 0.35);

        Optional<TradeSetup> result = detector.detect(ctx);

        assertTrue(result.isPresent(), "detector should fire on this sweep pattern");
        TradeSetup setup = result.get();
        double riskPct = (setup.entryPrice() - setup.stopPrice()) / setup.entryPrice();
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

    // --- PR4 rejection filters ---

    @Test
    @DisplayName("skips detection in low-ATR regime (atr/price < 0.3%)")
    void rejectsWhenAtrBelowRegimeThreshold() {
        // atr=0.2 on $100 → atr/price = 0.002 < MIN_ATR_PCT (0.003). Regime guard fires.
        CandleBar trigger = buildBullishSweepTrigger(99.0, 101.0, 97.0, 100.5);
        MarketContext ctx = buildContext(100.0, trigger, 98.0, 0.2);

        Optional<TradeSetup> result = detector.detect(ctx);
        assertTrue(result.isEmpty(), "low-ATR regime should not produce LS signals");
    }

    @Test
    @DisplayName("skips detection when pierce is shallower than 0.3 ATR")
    void rejectsWhenPierceTooShallow() {
        // atr=2.0, pierce threshold = 0.6. Set trigger.low only 0.3 below swingLow.
        CandleBar trigger = buildBullishSweepTrigger(99.0, 101.0, 97.7, 100.5);
        MarketContext ctx = buildContext(100.0, trigger, 98.0, 2.0);
        //            swingLow=98   pierce = 98 - 97.7 = 0.3   required = 0.6

        Optional<TradeSetup> result = detector.detect(ctx);
        assertTrue(result.isEmpty(), "shallow pierce should be classified as noise");
    }

    @Test
    @DisplayName("skips detection when close reclaims less than body × 0.3 above swing")
    void rejectsWhenReclaimTooShallow() {
        // body = |98.2 - 97.5| = 0.7. Reclaim threshold = swingLow 98 + 0.7*0.3 = 98.21.
        // close at 98.2 → barely above swing, below threshold → reject.
        CandleBar trigger = buildBullishSweepTrigger(97.5, 98.5, 97.0, 98.2);
        MarketContext ctx = buildContext(100.0, trigger, 98.0, 2.0);

        Optional<TradeSetup> result = detector.detect(ctx);
        assertTrue(result.isEmpty(), "failed-retest close should not fire");
    }

    @Test
    @DisplayName("skips detection when current price drifted > 0.5% from trigger close")
    void rejectsWhenDriftTooWide() {
        // trigger.close=100, currentPrice=101 → drift=1% > 0.5% MAX_DRIFT_PCT
        CandleBar trigger = buildBullishSweepTrigger(99.0, 101.0, 97.0, 100.0);
        MarketContext ctx = buildContextWithCurrentPrice(101.0, trigger, 98.0, 2.0);

        Optional<TradeSetup> result = detector.detect(ctx);
        assertTrue(result.isEmpty(), "wide drift from trigger close should reject entry");
    }

    @Test
    @DisplayName("skips detection when derivatives dimension opposes reversal by > 5")
    void rejectsWhenDerivativesOpposes() {
        // For LONG, opposing deriv score = -10 exceeds the 5.0 tolerance.
        CandleBar trigger = buildBullishSweepTrigger(99.0, 101.0, 97.0, 100.5);
        MarketContext ctx = buildContextWithDeriv(100.0, trigger, 98.0, 2.0, -10.0);

        Optional<TradeSetup> result = detector.detect(ctx);
        assertTrue(result.isEmpty(), "deriv dimension opposing > tolerance should reject");
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
        return buildContextInternal(entry, entry, trigger, swingLow, atr, 0.0);
    }

    private MarketContext buildContextWithCurrentPrice(double currentPrice, CandleBar trigger,
                                                       double swingLow, double atr) {
        return buildContextInternal(currentPrice, currentPrice, trigger, swingLow, atr, 0.0);
    }

    private MarketContext buildContextWithDeriv(double entry, CandleBar trigger,
                                                double swingLow, double atr, double derivScore) {
        return buildContextInternal(entry, entry, trigger, swingLow, atr, derivScore);
    }

    private MarketContext buildContextInternal(double entry, double currentPrice,
                                               CandleBar trigger, double swingLow,
                                               double atr, double derivScore) {
        List<CandleBar> bars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bars.add(new CandleBar(
                    Instant.now().minusSeconds((10 - i) * 14400L),
                    entry * 1.01, entry * 1.02, swingLow, entry * 1.015));
        }
        bars.add(trigger);
        bars.add(new CandleBar(
                Instant.now(), entry, entry * 1.001, entry * 0.999, entry));

        Map<String, Object> indicators = new HashMap<>();
        indicators.put("atr14", atr);
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("technicalIndicators", indicators);

        Map<String, Double> dimensionScores = new HashMap<>();
        dimensionScores.put("Derivatives", derivScore);

        return new MarketContext(
                "TESTUSDT", currentPrice, analytics, Map.of(), Map.of(), Map.of(),
                dimensionScores, bars);
    }
}
