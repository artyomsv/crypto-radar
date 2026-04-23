package com.cryptoradar.execution.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the stagnation decision. Scheduler + DB-read paths
 * are exercised by live smoke; the pure comparison against thresholds
 * is pinned here so tuning tweaks cannot silently shift behaviour.
 */
class StagnationMonitorTest {

    private StagnationMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new StagnationMonitor();
        monitor.enabled = true;
        monitor.minAgeMinutes = 45;
        monitor.mfeThresholdPct = 0.2;
        monitor.maeFloorPct = -0.3;
    }

    @Test
    void stagnantExcursionTriggers() {
        assertTrue(monitor.isStagnant(new StagnationMonitor.Excursion(0.1, -0.1)));
        assertTrue(monitor.isStagnant(new StagnationMonitor.Excursion(0.0, 0.0)));
        assertTrue(monitor.isStagnant(new StagnationMonitor.Excursion(0.15, -0.25)));
    }

    @Test
    void mfeAboveThresholdIsNotStagnant() {
        // Position moved favorably past the threshold — let it run.
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.2, -0.1)));
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.5, -0.1)));
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(1.0, -0.1)));
    }

    @Test
    void maeBelowFloorIsNotStagnant() {
        // Position is losing hard — let the stop handle it, don't
        // close early and confuse the trade ledger.
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.1, -0.3)));
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.1, -0.5)));
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.1, -1.0)));
    }

    @Test
    void mfeExactlyAtThresholdIsNotStagnant() {
        // Strict inequality: at the threshold, the trade is considered moving.
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.2, -0.1)));
    }

    @Test
    void maeExactlyAtFloorIsNotStagnant() {
        // Strict inequality: at the floor, the trade is considered losing.
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.1, -0.3)));
    }

    @Test
    void customThresholdsApply() {
        // Tighter MFE threshold with same MAE floor — a 0.15 MFE trade
        // that was stagnant under defaults is now "moving enough".
        monitor.mfeThresholdPct = 0.1;
        assertFalse(monitor.isStagnant(new StagnationMonitor.Excursion(0.15, -0.1)));
    }
}
