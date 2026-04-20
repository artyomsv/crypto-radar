package com.cryptoradar.execution.policy;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutionEventType;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure evaluator for the six capital-preservation guardrails. Rule order:
 * kill_switch -> auto_trade -> signal_age -> max_concurrent -> daily_halt -> dedup.
 * First match wins (short-circuit).
 *
 * <p>Callers supply the runtime state (open-position count, today's realized
 * P&amp;L percent, whether a duplicate open trade exists for the symbol+direction
 * +strategy triple) — this class only evaluates thresholds against that state.
 */
@ApplicationScoped
public class GuardrailPolicy {

    public record SignalCandidate(String symbol, String direction, String strategy,
                                   String signalId, Instant signalTime) {}

    public record Decision(boolean accepted, ExecutionEventType blockReason) {
        public static Decision accept() { return new Decision(true, null); }
        public static Decision block(ExecutionEventType reason) { return new Decision(false, reason); }
    }

    public Decision evaluate(ExchangeAccount account, SignalCandidate candidate,
                              int openPositions, BigDecimal todayPnlPercent, boolean dedupHit) {
        if (account.isKillSwitch()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_KILL_SWITCH);
        }
        if (!account.isAutoTradeEnabled()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_AUTO_TRADE_OFF);
        }
        long age = Duration.between(candidate.signalTime(), Instant.now()).getSeconds();
        if (age > account.getSignalAgeSeconds()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_SIGNAL_AGE);
        }
        if (openPositions >= account.getMaxConcurrentPositions()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_MAX_CONCURRENT);
        }
        if (todayPnlPercent != null
                && todayPnlPercent.compareTo(account.getMaxDailyLossPercent().negate()) < 0) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_DAILY_HALT);
        }
        if (dedupHit) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_DEDUP);
        }
        return Decision.accept();
    }
}
