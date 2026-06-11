package com.cryptoradar.execution.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DonchianExitDecisionTest {

    @Test
    void exitLookback_is10ForDonchianAndS1_20ForS2() {
        assertEquals(10, DonchianExitDecision.exitLookback("donchian"));
        assertEquals(10, DonchianExitDecision.exitLookback("turtle-s1"));
        assertEquals(20, DonchianExitDecision.exitLookback("turtle-s2"));
    }

    @Test
    void exitLookback_unknownStrategy_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> DonchianExitDecision.exitLookback("dimension"));
    }

    @Test
    void longExitsWhenPriceAtOrBelowReverseLow() {
        assertTrue(DonchianExitDecision.shouldExit(true, 99.0, 100.0, 120.0));   // price <= low
        assertTrue(DonchianExitDecision.shouldExit(true, 100.0, 100.0, 120.0));  // equal -> exit
        assertFalse(DonchianExitDecision.shouldExit(true, 101.0, 100.0, 120.0)); // above low -> hold
    }

    @Test
    void shortExitsWhenPriceAtOrAboveReverseHigh() {
        assertTrue(DonchianExitDecision.shouldExit(false, 121.0, 100.0, 120.0));  // price >= high
        assertTrue(DonchianExitDecision.shouldExit(false, 120.0, 100.0, 120.0));  // equal -> exit
        assertFalse(DonchianExitDecision.shouldExit(false, 119.0, 100.0, 120.0)); // below high -> hold
    }
}
