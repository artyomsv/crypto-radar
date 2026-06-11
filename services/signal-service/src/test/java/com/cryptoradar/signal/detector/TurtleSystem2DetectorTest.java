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

class TurtleSystem2DetectorTest {

    private final TurtleSystem2Detector detector = new TurtleSystem2Detector();

    @BeforeEach
    void enableDetector() {
        detector.enabled = true;
    }

    private MarketContext ctx(double price, DonchianSnapshot snap) {
        return new MarketContext("SOLUSDT", price,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap);
    }

    // high55=120, low55=80; the 20-day channel (110/90) must NOT trigger S2
    private DonchianSnapshot snap() {
        return new DonchianSnapshot(110, 90, 108, 92, 120, 80, 2.0, true, Instant.now());
    }

    @Test
    void firesLongOnHigh55Break() {
        Optional<TradeSetup> r = detector.detect(ctx(120.5, snap()));
        assertTrue(r.isPresent());
        assertEquals("turtle-s2", r.get().strategy());
        assertEquals("LONG", r.get().direction());
    }

    @Test
    void ignoresLoserFilter_firesEvenWhenLastWinnerTrue() {
        // S2 has no loser-filter; snap().lastS1BreakoutWasWinner()==true must not block
        assertTrue(detector.detect(ctx(120.5, snap())).isPresent());
    }

    @Test
    void doesNotFireOn20DayBreakOnly() {
        // price breaks high20 (110) but not high55 (120) -> S2 silent
        assertTrue(detector.detect(ctx(111.0, snap())).isEmpty());
    }

    @Test
    void firesShortOnLow55Break() {
        assertEquals("SHORT", detector.detect(ctx(79.5, snap())).get().direction());
    }

    @Test
    void silentWhenNoSnapshot() {
        assertTrue(detector.detect(ctx(120.5, null)).isEmpty());
    }

    @Test
    void silentWhenNoPrice() {
        MarketContext c = new MarketContext("SOLUSDT", null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap());
        assertTrue(detector.detect(c).isEmpty());
    }

    @Test
    void silentWhenDisabled() {
        detector.enabled = false;
        assertTrue(detector.detect(ctx(120.5, snap())).isEmpty());
    }
}
