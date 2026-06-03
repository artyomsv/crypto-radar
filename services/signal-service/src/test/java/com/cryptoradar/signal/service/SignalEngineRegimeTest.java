package com.cryptoradar.signal.service;

import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.model.MarketRegime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Validates regime-adjusted thresholds. The end-to-end scoring pipeline
 * couples confidence to score magnitude (higher confidence requires
 * stronger alignment, which also produces bigger |score|), so a
 * "modest bearish scenario produces SELL but not STRONG_SELL in CHOP,
 * and NEUTRAL in BULL" test is empirically awkward to set up. Instead
 * we verify the raw threshold tuple — the scoring integration is
 * covered by {@code SignalEngineBiasTest} for the CHOP path.
 *
 * <p>Thresholds tuple layout: {@code [strongBuyMin, buyMin, strongSellMax, sellMax]}.
 */
class SignalEngineRegimeTest {

    private final SignalEngine engine = new SignalEngine(new FakeConfigService(SignalConfig.defaults()));

    @Test
    @DisplayName("CHOP regime uses default asymmetric thresholds")
    void chopUsesDefaults() {
        int[] got = engine.regimeAdjustedThresholds(MarketRegime.CHOP);
        assertArrayEquals(new int[]{55, 25, -40, -15}, got,
                "CHOP should use PR3 transitional asymmetric defaults");
    }

    @Test
    @DisplayName("UNKNOWN regime falls back to CHOP defaults")
    void unknownFallsBackToChop() {
        assertArrayEquals(
                engine.regimeAdjustedThresholds(MarketRegime.CHOP),
                engine.regimeAdjustedThresholds(MarketRegime.UNKNOWN),
                "UNKNOWN must not be stricter than CHOP");
    }

    @Test
    @DisplayName("BULL regime tightens SELL side — counter-trend shorts need more evidence")
    void bullTightensSellSide() {
        int[] got = engine.regimeAdjustedThresholds(MarketRegime.BULL);
        // BUY side unchanged vs CHOP; SELL thresholds revert to symmetric (harder).
        assertArrayEquals(new int[]{55, 25, -55, -30}, got,
                "BULL should keep BUY defaults and raise SELL threshold bar");
    }

    @Test
    @DisplayName("BEAR regime tightens BUY side — counter-trend longs need more evidence")
    void bearTightensBuySide() {
        int[] got = engine.regimeAdjustedThresholds(MarketRegime.BEAR);
        // SELL side unchanged vs CHOP; BUY thresholds raised.
        assertArrayEquals(new int[]{70, 40, -40, -15}, got,
                "BEAR should keep SELL defaults and raise BUY threshold bar");
    }
}
