package com.cryptoradar.marketdata.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks the per-fetch staleness counter logic. The startup scan path is
 * exercised indirectly when the service boots against a live DB — testing
 * it here would require a fake EntityManager which the JPA contract makes
 * impractical without adding a Mockito dependency.
 */
class StalenessDetectorTest {

    @Test
    void firstFetchSeedsStateWithZeroStale() {
        StalenessDetector det = new StalenessDetector();
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        StalenessDetector.FetchState state = det.snapshot().get("BTCUSDT|1m");
        assertNotNull(state);
        assertEquals(1700000000000L, state.newestEpochMs());
        assertEquals(0, state.consecutiveStale());
    }

    @Test
    void advancingEpochResetsConsecutiveCounter() {
        StalenessDetector det = new StalenessDetector();
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);   // same → +1
        det.recordFetch("BTCUSDT", "1m", 1700000060000L);   // advance → reset
        StalenessDetector.FetchState state = det.snapshot().get("BTCUSDT|1m");
        assertEquals(1700000060000L, state.newestEpochMs());
        assertEquals(0, state.consecutiveStale());
    }

    @Test
    void identicalEpochIncrementsStaleCounter() {
        StalenessDetector det = new StalenessDetector();
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        StalenessDetector.FetchState state = det.snapshot().get("BTCUSDT|1m");
        assertEquals(2, state.consecutiveStale());
    }

    @Test
    void olderEpochAlsoIncrementsStaleCounter() {
        // An older bar arriving is still a "no progress" signal — count it.
        StalenessDetector det = new StalenessDetector();
        det.recordFetch("BTCUSDT", "1m", 1700000060000L);
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);   // older
        StalenessDetector.FetchState state = det.snapshot().get("BTCUSDT|1m");
        assertEquals(1700000060000L, state.newestEpochMs(),
                "newestEpochMs should track the high-water mark, not the latest fetch");
        assertEquals(1, state.consecutiveStale());
    }

    @Test
    void differentSymbolsTrackedIndependently() {
        StalenessDetector det = new StalenessDetector();
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        det.recordFetch("ETHUSDT", "1m", 1700000000000L);
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        assertEquals(1, det.snapshot().get("BTCUSDT|1m").consecutiveStale());
        assertEquals(0, det.snapshot().get("ETHUSDT|1m").consecutiveStale());
    }

    @Test
    void autoDeactivateThresholdConstantMatchesTwoHoursOfMinutes() {
        // Sanity check — the chosen constant must allow the symbol to be
        // genuinely frozen for ~3 hours of 1m fetches before flipping.
        // Lower values flag legitimate exchange-side outages; higher delays
        // waste API quota. Locked at 180.
        assertEquals(180, StalenessDetector.AUTO_DEACTIVATE_STALE_FETCHES_THRESHOLD);
    }

    @Test
    void differentIntervalsTrackedIndependently() {
        StalenessDetector det = new StalenessDetector();
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        det.recordFetch("BTCUSDT", "5m", 1700000000000L);
        det.recordFetch("BTCUSDT", "1m", 1700000000000L);
        assertEquals(1, det.snapshot().get("BTCUSDT|1m").consecutiveStale());
        assertEquals(0, det.snapshot().get("BTCUSDT|5m").consecutiveStale());
    }
}
