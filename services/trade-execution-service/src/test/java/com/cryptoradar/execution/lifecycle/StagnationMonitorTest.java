package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void skipsLongHorizonStrategyTrades() {
        // A donchian (long-horizon) trade that is old enough and carries a
        // signal id — it would otherwise reach the stagnation check. The
        // long-horizon guard is the first statement in the loop, so the trade
        // must never be closed regardless of its excursion.
        ExecutedTradeRepository tradeRepo = mock(ExecutedTradeRepository.class);
        OrderPlacer orderPlacer = mock(OrderPlacer.class);
        StrategyExitPolicy exitPolicy = new StrategyExitPolicy();
        exitPolicy.longHorizonCsv = "donchian,turtle-s1,turtle-s2";
        monitor.tradeRepo = tradeRepo;
        monitor.orderPlacer = orderPlacer;
        monitor.exitPolicy = exitPolicy;

        ExchangeAccount account = new ExchangeAccount();
        Instant ageThreshold = Instant.now().minus(monitor.minAgeMinutes, ChronoUnit.MINUTES);

        ExecutedTrade donchian = new ExecutedTrade();
        donchian.setSymbol("BTCUSDT");
        donchian.setDirection("LONG");
        donchian.setStrategy("donchian");
        donchian.setSignalId("sig-1");
        donchian.setOpenedAt(Instant.now().minus(2, ChronoUnit.HOURS)); // older than threshold
        when(tradeRepo.findOpenForAccount(account.getId())).thenReturn(List.of(donchian));

        int closed = monitor.sweepForAccount(account, ageThreshold);

        assertEquals(0, closed);
        verify(orderPlacer, never()).close(any(), any(), any());
    }
}
