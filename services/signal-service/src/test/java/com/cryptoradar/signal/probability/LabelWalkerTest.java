package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabelWalkerTest {

    private static CandleBar bar(double high, double low) {
        return new CandleBar(Instant.parse("2026-01-01T00:00:00Z"), low, high, low, low, 0.0);
    }

    @Test
    void longHitsTargetBeforeStop() {
        List<CandleBar> fwd = List.of(bar(101, 99), bar(106, 104)); // entry 100, target 105
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void longHitsStopBeforeTarget() {
        List<CandleBar> fwd = List.of(bar(101, 94)); // low 94 <= stop 95
        assertEquals(ProbabilityCandidate.STATUS_HIT_STOP,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void straddleBarCountsAsStopForLong() {
        List<CandleBar> fwd = List.of(bar(106, 94)); // both stop(95) and target(105) inside
        assertEquals(ProbabilityCandidate.STATUS_HIT_STOP,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void neitherHitWithinWindowExpires() {
        List<CandleBar> fwd = List.of(bar(101, 99), bar(102, 98));
        assertEquals(ProbabilityCandidate.STATUS_EXPIRED,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void shortMirrorsLong() {
        List<CandleBar> fwd = List.of(bar(101, 94)); // short entry 100, target 95 reached at low 94
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET,
                LabelWalker.resolve(fwd, 100, 105, 95, false));
    }
}
