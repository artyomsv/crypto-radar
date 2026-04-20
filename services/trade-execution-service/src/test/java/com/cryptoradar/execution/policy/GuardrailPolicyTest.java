package com.cryptoradar.execution.policy;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.policy.GuardrailPolicy.Decision;
import com.cryptoradar.execution.policy.GuardrailPolicy.SignalCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailPolicyTest {

    private GuardrailPolicy policy;
    private ExchangeAccount acct;

    @BeforeEach
    void setup() {
        policy = new GuardrailPolicy();
        acct = new ExchangeAccount();
        acct.setAutoTradeEnabled(true);
        acct.setKillSwitch(false);
        acct.setMaxConcurrentPositions(5);
        acct.setMaxDailyLossPercent(new BigDecimal("5.00"));
        acct.setSignalAgeSeconds(60);
    }

    private SignalCandidate fresh() {
        return new SignalCandidate("BTCUSDT", "LONG", "trend-continuation", "sig-1", Instant.now());
    }

    @Test
    void acceptHappyPath() {
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertTrue(d.accepted());
    }

    @Test
    void killSwitchBlocks() {
        acct.setKillSwitch(true);
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_KILL_SWITCH, d.blockReason());
    }

    @Test
    void autoTradeOffBlocks() {
        acct.setAutoTradeEnabled(false);
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_AUTO_TRADE_OFF, d.blockReason());
    }

    @Test
    void signalAgeBlocks() {
        SignalCandidate stale = new SignalCandidate("BTCUSDT", "LONG", "t", "s",
                Instant.now().minusSeconds(120));  // 2 min old, limit 60s
        Decision d = policy.evaluate(acct, stale, 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_SIGNAL_AGE, d.blockReason());
    }

    @Test
    void maxConcurrentBlocks() {
        Decision d = policy.evaluate(acct, fresh(), 5, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_MAX_CONCURRENT, d.blockReason());
    }

    @Test
    void dailyLossHaltBlocks() {
        // Loss 6% when limit 5%
        Decision d = policy.evaluate(acct, fresh(), 0, new BigDecimal("-6.0"), false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_DAILY_HALT, d.blockReason());
    }

    @Test
    void dedupBlocks() {
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, true);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_DEDUP, d.blockReason());
    }

    @Test
    void ruleOrderingKillSwitchBeforeAutoTrade() {
        // Both conditions true; kill_switch should win
        acct.setKillSwitch(true);
        acct.setAutoTradeEnabled(false);
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_KILL_SWITCH, d.blockReason());
    }
}
