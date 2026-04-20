package com.cryptoradar.execution.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.cryptoradar.execution.intake.FlipTracker.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlipTrackerTest {

    private FlipTracker tracker;

    @BeforeEach
    void setup() {
        tracker = new FlipTracker();
    }

    @Test
    void singleStrongBuyOnFreshSymbolIsNoAction() {
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));
    }

    @Test
    void twoConsecutiveStrongBuyTriggersEnterLong() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));
    }

    @Test
    void twoConsecutiveStrongSellTriggersEnterShort() {
        tracker.observe("BTCUSDT", "STRONG_SELL", 2, false, false);
        assertEquals(ENTER_SHORT, tracker.observe("BTCUSDT", "STRONG_SELL", 2, false, false));
    }

    @Test
    void persistenceOneFiresImmediately() {
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 1, false, false));
    }

    @Test
    void oppositeSignalResetsStreak() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_SELL", 2, false, false));
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));   // back to 1 count
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));  // now 2
    }

    @Test
    void neutralSignalClearsState() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "NEUTRAL", 2, false, false));
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));  // streak=1
    }

    @Test
    void twoStrongSellsOnSymbolWeAreLongOnClosesLong() {
        tracker.observe("BTCUSDT", "STRONG_SELL", 2, true, false);
        assertEquals(CLOSE_LONG, tracker.observe("BTCUSDT", "STRONG_SELL", 2, true, false));
    }

    @Test
    void twoStrongBuysOnSymbolWeAreShortOnClosesShort() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, true);
        assertEquals(CLOSE_SHORT, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, true));
    }

    @Test
    void stateIsPerSymbol() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        // ETH streak starts fresh
        assertEquals(NO_ACTION, tracker.observe("ETHUSDT", "STRONG_BUY", 2, false, false));
        // BTC still at streak=1 from earlier
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));
    }

    @Test
    void sameDirectionWhenAlreadyHoldingIsNoAction() {
        // We're already LONG, another STRONG_BUY sequence arrives — no new action
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, true, false);
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, true, false));
    }
}
