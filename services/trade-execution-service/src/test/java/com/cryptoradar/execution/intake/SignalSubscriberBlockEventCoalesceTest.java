package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.model.ExecutionEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 60s coalescing of SIGNAL_BLOCKED_* events is what keeps
 * execution_events from accumulating 100k+ noise rows/day during CHOP
 * regimes where most symbols sit below the alignment floor. This test
 * locks the coalescing contract: distinct keys never collide, identical
 * keys deduplicate within the window.
 */
class SignalSubscriberBlockEventCoalesceTest {

    @Test
    void firstEmitForKeyIsAllowed() {
        SignalSubscriber s = new SignalSubscriber();
        assertTrue(s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "LONG"));
    }

    @Test
    void secondEmitWithinWindowIsBlocked() {
        SignalSubscriber s = new SignalSubscriber();
        s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "LONG");
        assertFalse(s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "LONG"));
    }

    @Test
    void differentSymbolsDoNotCollide() {
        SignalSubscriber s = new SignalSubscriber();
        s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "LONG");
        assertTrue(s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "ETHUSDT", "LONG"));
    }

    @Test
    void differentDirectionsDoNotCollide() {
        SignalSubscriber s = new SignalSubscriber();
        s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "LONG");
        assertTrue(s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "SHORT"));
    }

    @Test
    void differentGateTypesDoNotCollide() {
        SignalSubscriber s = new SignalSubscriber();
        s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_ALIGNMENT_FLOOR, "BTCUSDT", "LONG");
        assertTrue(s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_SYMBOL_PERF, "BTCUSDT", "LONG"));
        assertTrue(s.shouldEmitBlockEvent(
                ExecutionEventType.SIGNAL_BLOCKED_CONFLUENCE, "BTCUSDT", "LONG"));
    }
}
