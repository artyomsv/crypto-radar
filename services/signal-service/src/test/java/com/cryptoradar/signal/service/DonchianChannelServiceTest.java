package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DonchianSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DonchianChannelServiceTest {

    private final DonchianChannelService service = new DonchianChannelService(null, null);

    /** 60 oldest-first daily bars; the LAST bar is "today" and must be excluded. */
    private List<CandleBar> bars(double todayHigh, double todayLow) {
        List<CandleBar> bars = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 59; i++) {
            // completed history: highs 100..158, lows 90..148
            double high = 100 + i;
            double low = 90 + i;
            bars.add(new CandleBar(t.plusSeconds(i * 86400L), low + 1, high, low, high - 1));
        }
        // today's forming bar — extreme values that must NOT enter the channel
        bars.add(new CandleBar(t.plusSeconds(59 * 86400L), 1, todayHigh, todayLow, 1));
        return bars;
    }

    @Test
    void buildSnapshot_excludesTodayBar_fromChannels() {
        DonchianSnapshot snap = service.buildSnapshot(bars(9999, -1), true, false);
        // last 20 completed highs are 139..158 -> high20 = 158
        assertEquals(158.0, snap.high20());
        // last 55 completed highs end at 158 -> high55 = 158
        assertEquals(158.0, snap.high55());
        // last 10 completed lows are 139..148 -> low10 = 139
        assertEquals(139.0, snap.low10());
        // last 20 completed lows are 129..148 -> low20 = 129
        assertEquals(129.0, snap.low20());
        // last 55 completed lows start at 94 -> low55 = 94
        assertEquals(94.0, snap.low55());
        // last 10 completed highs are 149..158 -> high10 = 158
        assertEquals(158.0, snap.high10());
        assertTrue(snap.n() > 0);
        assertTrue(snap.lastS1LongWasWinner());
        assertFalse(snap.lastS1ShortWasWinner());
    }

    @Test
    void buildSnapshot_passesThroughLoserFlagFalse() {
        DonchianSnapshot snap = service.buildSnapshot(bars(200, 80), false, false);
        assertFalse(snap.lastS1LongWasWinner());
    }

    @Test
    void buildSnapshot_throwsWhenInsufficientHistory() {
        List<CandleBar> tooFew = bars(200, 80).subList(0, 40); // < 56 bars
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSnapshot(tooFew, false, false));
    }
}
