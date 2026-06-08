package com.cryptoradar.signal.service;

import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.model.TradeSetup;
import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for OutcomeTracker's pure-logic helpers. The persistence path
 * (findOpenByStrategy + persist) is exercised via integration tests upstream;
 * this suite locks the build/routing logic that has historically had bugs:
 *
 * <ul>
 *   <li>"Order Book" dimension name (the space caused 3 weeks of orderbook_score
 *       NULLs pre-v4 — guarded here)</li>
 *   <li>BUY/STRONG_BUY → LONG, SELL/STRONG_SELL → SHORT direction resolution</li>
 *   <li>TrailConfig precedence: per-setup override beats engine-wide default</li>
 *   <li>isTrackable rejects signals with missing trade levels</li>
 * </ul>
 */
class OutcomeTrackerTest {

    private OutcomeTracker tracker;

    @BeforeEach
    void setUp() {
        // Repository + ConfigService unused by the package-private helpers.
        // The full integration test elsewhere covers the persist + dedup path.
        tracker = new OutcomeTracker(null, null);
    }

    @Test
    @DisplayName("BUY and STRONG_BUY → LONG")
    void buyResolvesToLong() {
        assertEquals("LONG", tracker.resolveDirection("BUY"));
        assertEquals("LONG", tracker.resolveDirection("STRONG_BUY"));
    }

    @Test
    @DisplayName("SELL and STRONG_SELL → SHORT")
    void sellResolvesToShort() {
        assertEquals("SHORT", tracker.resolveDirection("SELL"));
        assertEquals("SHORT", tracker.resolveDirection("STRONG_SELL"));
    }

    @Test
    @DisplayName("NEUTRAL / null / unknown → null direction")
    void neutralResolvesToNull() {
        assertNull(tracker.resolveDirection("NEUTRAL"));
        assertNull(tracker.resolveDirection(null));
        assertNull(tracker.resolveDirection("WAT"));
    }

    @Test
    @DisplayName("isTrackable rejects signals missing entry / stop / target / RR")
    void incompleteSignalsNotTrackable() {
        TradingSignal s = new TradingSignal();
        s.setSignal("BUY");
        assertFalse(tracker.isTrackable(s));

        s.setSuggestedEntry(100.0);
        assertFalse(tracker.isTrackable(s));

        s.setSuggestedStopLoss(99.0);
        assertFalse(tracker.isTrackable(s));

        s.setSuggestedTakeProfit(102.0);
        assertFalse(tracker.isTrackable(s));

        s.setRiskRewardRatio(2.0);
        assertTrue(tracker.isTrackable(s));
    }

    @Test
    @DisplayName("isTrackable rejects NEUTRAL even with complete levels")
    void neutralWithLevelsNotTrackable() {
        TradingSignal s = signalWith("NEUTRAL", 100.0, 99.0, 102.0, 2.0);
        assertFalse(tracker.isTrackable(s));
    }

    @Test
    @DisplayName("applyDimensionScores routes 'Order Book' (with space) correctly")
    void orderBookSpaceNameRoutes() {
        // Pre-v4 bug: the engine emitted "Order Book" (with space) but the
        // tracker checked for "OrderBook". orderbook_score stayed NULL for
        // weeks. This test pins the contract.
        SignalOutcome out = new SignalOutcome();
        tracker.applyDimensionScores(out, List.of(
                new DimensionScore("Order Book", 42.0, 0.1, List.of()),
                new DimensionScore("Technical", 80.0, 0.35, List.of()),
                new DimensionScore("Whale", -20.0, 0.25, List.of())
        ));
        assertEquals(42.0, out.getOrderbookScore());
        assertEquals(80.0, out.getTechnicalScore());
        assertEquals(-20.0, out.getWhaleScore());
    }

    @Test
    @DisplayName("applyDimensionScores ignores unknown dimension names")
    void unknownDimensionIgnored() {
        SignalOutcome out = new SignalOutcome();
        tracker.applyDimensionScores(out, List.of(
                new DimensionScore("Astrology", 100.0, 0.5, List.of())
        ));
        assertNull(out.getTechnicalScore());
        assertNull(out.getWhaleScore());
        assertNull(out.getDerivativesScore());
    }

    @Test
    @DisplayName("applyTrailConfig uses per-setup config when present")
    void perSetupTrailConfigTakesPrecedence() {
        SignalOutcome out = new SignalOutcome();
        TrailConfig custom = new TrailConfig(1.5, 0.5, 0.75, 3.0, 1.0);
        tracker.applyTrailConfig(out, custom);
        assertEquals(1.5, out.getTrailActivationR());
        assertEquals(0.5, out.getTrailStepR());
        assertEquals(0.75, out.getTrailOffsetR());
    }

    @Test
    @DisplayName("applyTrailConfig falls back to nothing when config null and configService null")
    void nullConfigDefaultsLeftUnset() {
        // tracker has configService = null per setUp() — safe path: no NPE,
        // outcome's trail fields stay at their default (null doubles → 0.0).
        SignalOutcome out = new SignalOutcome();
        tracker.applyTrailConfig(out, null);
        // No assertions on values — the contract is "doesn't throw".
        assertNotNull(out);
    }

    @Test
    @DisplayName("buildOutcomeFromSetup copies all setup fields onto outcome")
    void buildOutcomeFromSetupCopiesFields() {
        TrailConfig trail = new TrailConfig(1.0, 0.5, 0.75, 2.5, 1.0);
        TradeSetup setup = new TradeSetup(
                "trend-continuation", "BTCUSDT", "LONG", "BUY",
                50000.0, 49500.0, 51500.0, 3.0, 65,
                List.of("trend HTF up", "pullback OK", "RSI in band"),
                Instant.parse("2026-06-01T12:00:00Z"),
                trail);

        SignalOutcome out = tracker.buildOutcomeFromSetup(setup);

        assertEquals("trend-continuation", out.getStrategy());
        assertEquals("BTCUSDT", out.getSymbol());
        assertEquals("LONG", out.getDirection());
        assertEquals("BUY", out.getSignalType());
        assertEquals(50000.0, out.getEntryPrice());
        assertEquals(49500.0, out.getStopPrice());
        assertEquals(51500.0, out.getTargetPrice());
        assertEquals(3.0, out.getRiskRewardRatio());
        assertEquals(65, out.getAlignment());
        assertEquals(65.0, out.getOverallScore());
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), out.getFiredAt());
        assertNotNull(out.getSignalId(), "signal_id must be UUID-stamped at build time");
        assertEquals("trend HTF up | pullback OK | RSI in band", out.getAiAnalysis());
        // Trail config copied through
        assertEquals(1.0, out.getTrailActivationR());
        assertEquals(0.75, out.getTrailOffsetR());
    }

    @Test
    @DisplayName("buildOutcomeFromSetup uses Instant.now() when firedAt is null")
    void buildOutcomeFromSetupNullFiredAtUsesNow() {
        TradeSetup setup = new TradeSetup(
                "liquidity-sweep", "ETHUSDT", "SHORT", "SELL",
                3000.0, 3050.0, 2900.0, 2.0, 70,
                List.of(), null, TrailConfig.DEFAULT);

        Instant before = Instant.now();
        SignalOutcome out = tracker.buildOutcomeFromSetup(setup);
        Instant after = Instant.now();

        assertTrue(out.getFiredAt().compareTo(before) >= 0,
                "firedAt should default to current time when null");
        assertTrue(out.getFiredAt().compareTo(after) <= 0);
    }

    private TradingSignal signalWith(String type, double entry, double stop, double target, double rr) {
        TradingSignal s = new TradingSignal();
        s.setSignal(type);
        s.setSuggestedEntry(entry);
        s.setSuggestedStopLoss(stop);
        s.setSuggestedTakeProfit(target);
        s.setRiskRewardRatio(rr);
        return s;
    }
}
