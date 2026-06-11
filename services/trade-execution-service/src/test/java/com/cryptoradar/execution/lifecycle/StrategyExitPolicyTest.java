package com.cryptoradar.execution.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyExitPolicyTest {

    private StrategyExitPolicy policy(String csv) {
        StrategyExitPolicy p = new StrategyExitPolicy();
        p.longHorizonCsv = csv;
        return p;
    }

    @Test
    void recognisesConfiguredLongHorizonStrategies() {
        StrategyExitPolicy p = policy("donchian,turtle-s1,turtle-s2");
        assertTrue(p.isLongHorizon("donchian"));
        assertTrue(p.isLongHorizon("turtle-s1"));
        assertTrue(p.isLongHorizon("turtle-s2"));
    }

    @Test
    void otherStrategiesAreNotLongHorizon() {
        StrategyExitPolicy p = policy("donchian,turtle-s1,turtle-s2");
        assertFalse(p.isLongHorizon("trend-continuation"));
        assertFalse(p.isLongHorizon("dimension"));
        assertFalse(p.isLongHorizon(null));
    }

    @Test
    void toleratesWhitespaceAndBlankEntries() {
        StrategyExitPolicy p = policy(" donchian , , turtle-s1 ");
        assertTrue(p.isLongHorizon("donchian"));
        assertTrue(p.isLongHorizon("turtle-s1"));
        assertFalse(p.isLongHorizon("turtle-s2"));
    }
}
