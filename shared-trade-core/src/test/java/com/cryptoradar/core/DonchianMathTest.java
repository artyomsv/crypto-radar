package com.cryptoradar.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DonchianMathTest {

    // highs/lows oldest-first; index 5 (the 6th bar) is "today" and is excluded
    private static final double[] HIGHS = {10, 11, 12, 11, 13, 99};
    private static final double[] LOWS  = { 9,  8,  7,  9,  6,  1};

    @Test
    void channelHigh_excludesCurrentBar_takesPriorLookback() {
        // endExclusive=5 excludes the 99 bar; max of first five highs = 13
        assertEquals(13.0, DonchianMath.channelHigh(HIGHS, 5, 5));
    }

    @Test
    void channelLow_excludesCurrentBar_takesPriorLookback() {
        // endExclusive=5 excludes the 1 bar; min of first five lows = 6
        assertEquals(6.0, DonchianMath.channelLow(LOWS, 5, 5));
    }

    @Test
    void channelHigh_shorterLookback_usesOnlyMostRecentCompletedBars() {
        // last 2 completed highs before index 5 are {11,13} -> 13
        assertEquals(13.0, DonchianMath.channelHigh(HIGHS, 5, 2));
    }

    @Test
    void breakoutDirection_longWhenAboveHigh() {
        assertEquals(DonchianMath.Breakout.LONG,
                DonchianMath.breakoutDirection(13.5, 13.0, 6.0));
    }

    @Test
    void breakoutDirection_shortWhenBelowLow() {
        assertEquals(DonchianMath.Breakout.SHORT,
                DonchianMath.breakoutDirection(5.5, 13.0, 6.0));
    }

    @Test
    void breakoutDirection_noneWhenInsideChannel() {
        assertEquals(DonchianMath.Breakout.NONE,
                DonchianMath.breakoutDirection(10.0, 13.0, 6.0));
    }

    @Test
    void channelHigh_throwsWhenNotEnoughHistory() {
        assertThrows(IllegalArgumentException.class,
                () -> DonchianMath.channelHigh(HIGHS, 3, 5));
    }
}
