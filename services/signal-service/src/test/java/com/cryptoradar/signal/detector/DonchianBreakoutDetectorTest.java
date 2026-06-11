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

class DonchianBreakoutDetectorTest {

    private final DonchianBreakoutDetector detector = new DonchianBreakoutDetector();

    // @ConfigProperty injection does not run for plain-new beans, so the flag
    // defaults to false. Set it explicitly or every "fires" case no-ops.
    @BeforeEach
    void enableDetector() {
        detector.enabled = true;
    }

    private MarketContext ctx(double price, DonchianSnapshot snap) {
        return new MarketContext("BTCUSDT", price,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap);
    }

    private DonchianSnapshot snap() {
        // high20=110, low20=90, N=2
        return new DonchianSnapshot(110, 90, 108, 92, 120, 80, 2.0, false, false, Instant.now());
    }

    @Test
    void firesLongWhenPriceBreaksHigh20() {
        Optional<TradeSetup> r = detector.detect(ctx(110.5, snap()));
        assertTrue(r.isPresent());
        TradeSetup s = r.get();
        assertEquals("donchian", s.strategy());
        assertEquals("LONG", s.direction());
        assertEquals(110.5 - 2 * 2.0, s.stopPrice(), 1e-9); // entry - 2N
    }

    @Test
    void firesShortWhenPriceBreaksLow20() {
        Optional<TradeSetup> r = detector.detect(ctx(89.5, snap()));
        assertTrue(r.isPresent());
        assertEquals("SHORT", r.get().direction());
        assertEquals(89.5 + 2 * 2.0, r.get().stopPrice(), 1e-9); // entry + 2N
    }

    @Test
    void silentInsideChannel() {
        assertTrue(detector.detect(ctx(100.0, snap())).isEmpty());
    }

    @Test
    void silentWhenNoSnapshot() {
        assertTrue(detector.detect(ctx(100.0, null)).isEmpty());
    }

    @Test
    void silentWhenNoPrice() {
        MarketContext c = new MarketContext("BTCUSDT", null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap());
        assertTrue(detector.detect(c).isEmpty());
    }

    @Test
    void silentWhenDisabled() {
        detector.enabled = false;
        assertTrue(detector.detect(ctx(110.5, snap())).isEmpty());
    }
}
