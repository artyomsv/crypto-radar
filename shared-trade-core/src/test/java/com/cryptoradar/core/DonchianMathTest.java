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

    @Test
    void channelLow_throwsWhenNotEnoughHistory() {
        assertThrows(IllegalArgumentException.class,
                () -> DonchianMath.channelLow(LOWS, 3, 5));
    }

    @Test
    void computeN_constantOnePointRange_equalsOne() {
        // Every bar has high-low = 1 and no gaps, so TR is 1 throughout -> N = 1.
        int n = 25;
        double[] highs = new double[n];
        double[] lows = new double[n];
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            highs[i] = 100.5;
            lows[i] = 99.5;
            closes[i] = 100.0;
        }
        assertEquals(1.0, DonchianMath.computeN(highs, lows, closes, 20), 1e-9);
    }

    @Test
    void computeN_throwsWhenSeriesShorterThanPeriodPlusOne() {
        double[] a = {1, 2, 3};
        assertThrows(IllegalArgumentException.class,
                () -> DonchianMath.computeN(a, a, a, 20));
    }

    @Test
    void computeN_throwsWhenArrayLengthsDiffer() {
        double[] highs = new double[25];
        double[] lows = new double[24];
        double[] closes = new double[25];
        assertThrows(IllegalArgumentException.class,
                () -> DonchianMath.computeN(highs, lows, closes, 20));
    }

    @Test
    void unitStop_longSubtractsTwoN_shortAddsTwoN() {
        assertEquals(96.0, DonchianMath.unitStop(100.0, 2.0, true, 2.0), 1e-9);
        assertEquals(104.0, DonchianMath.unitStop(100.0, 2.0, false, 2.0), 1e-9);
    }

    @Test
    void addTrigger_longAddsHalfN_shortSubtractsHalfN() {
        assertEquals(101.0, DonchianMath.addTrigger(100.0, 2.0, true, 0.5), 1e-9);
        assertEquals(99.0, DonchianMath.addTrigger(100.0, 2.0, false, 0.5), 1e-9);
    }
}
