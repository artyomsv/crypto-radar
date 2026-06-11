package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TurtleSystem1DetectorTest {

    private final TurtleSystem1Detector detector = new TurtleSystem1Detector();

    @BeforeEach
    void enableDetector() {
        detector.enabled = true;
    }

    private MarketContext ctx(double price, DonchianSnapshot snap) {
        return new MarketContext("ETHUSDT", price,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap);
    }

    private DonchianSnapshot snap(boolean lastWinner) {
        return new DonchianSnapshot(110, 90, 108, 92, 120, 80, 2.0, lastWinner, Instant.now());
    }

    @Test
    void firesLongOnHigh20Break_whenLastBreakoutWasLoser() {
        Optional<TradeSetup> r = detector.detect(ctx(110.5, snap(false)));
        assertTrue(r.isPresent());
        assertEquals("turtle-s1", r.get().strategy());
        assertEquals("LONG", r.get().direction());
    }

    @Test
    void skipsBreakout_whenLastBreakoutWasWinner() {
        // loser-filter: a winning prior S1 breakout suppresses the next entry
        assertTrue(detector.detect(ctx(110.5, snap(true))).isEmpty());
    }

    @Test
    void silentInsideChannel() {
        assertTrue(detector.detect(ctx(100.0, snap(false))).isEmpty());
    }

    @Test
    void silentWhenNoSnapshot() {
        assertTrue(detector.detect(ctx(110.5, null)).isEmpty());
    }

    @Test
    void silentWhenNoPrice() {
        MarketContext c = new MarketContext("ETHUSDT", null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap(false));
        assertTrue(detector.detect(c).isEmpty());
    }

    @Test
    void silentWhenDisabled() {
        detector.enabled = false;
        assertTrue(detector.detect(ctx(110.5, snap(false))).isEmpty());
    }
}
