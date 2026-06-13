package com.cryptoradar.execution.client.bybit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the offset math in {@link BybitClock}. No CDI, no
 * network — these prove that signing will track Bybit's clock regardless of
 * how far the host clock has drifted.
 */
class BybitClockTest {

    private static final long TOLERANCE_MS = 200;

    @Test
    void nowMillisDefaultsToHostClockWhenNeverSynced() {
        BybitClock clock = new BybitClock();
        long delta = Math.abs(clock.nowMillis() - System.currentTimeMillis());
        assertTrue(delta < TOLERANCE_MS, "unsynced clock should equal host clock, delta=" + delta);
        assertEquals(0L, clock.offsetMillis());
    }

    @Test
    void applyServerTimeMakesNowTrackServerWhenHostIsBehind() {
        BybitClock clock = new BybitClock();
        long serverAhead = System.currentTimeMillis() + 34_000; // host 34s behind, the real incident

        clock.applyServerTime(serverAhead);

        long projected = clock.nowMillis();
        assertTrue(Math.abs(projected - serverAhead) < TOLERANCE_MS,
                "nowMillis should track server clock, projected=" + projected + " server=" + serverAhead);
        assertTrue(Math.abs(clock.offsetMillis() - 34_000) < TOLERANCE_MS,
                "offset should be ~+34s, was " + clock.offsetMillis());
    }

    @Test
    void applyServerTimeHandlesHostAheadOfServer() {
        BybitClock clock = new BybitClock();
        long serverBehind = System.currentTimeMillis() - 20_000; // host 20s ahead

        clock.applyServerTime(serverBehind);

        assertTrue(Math.abs(clock.nowMillis() - serverBehind) < TOLERANCE_MS);
        assertTrue(clock.offsetMillis() < 0, "offset should be negative when host is ahead");
    }

    @Test
    void implausibleServerTimeIsIgnoredAndOffsetUnchanged() {
        BybitClock clock = new BybitClock();
        clock.applyServerTime(34_000); // ~1970, clearly garbage (not an epoch-ms value)

        assertEquals(0L, clock.offsetMillis(), "garbage server time must not corrupt the offset");
    }

    @Test
    void laterSyncReplacesEarlierOffset() {
        BybitClock clock = new BybitClock();
        clock.applyServerTime(System.currentTimeMillis() + 10_000);
        clock.applyServerTime(System.currentTimeMillis() + 50_000);

        assertTrue(Math.abs(clock.offsetMillis() - 50_000) < TOLERANCE_MS,
                "most recent sync should win, offset=" + clock.offsetMillis());
    }
}
