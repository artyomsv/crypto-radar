package com.cryptoradar.execution.observability;

import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExitReason;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Emits structured {@code execution_slippage} log events on entry fills and
 * trade closes. Pure observability — no behavior change.
 *
 * <p>Sign convention: positive bps = adverse to the trader (paid more on entry,
 * received less on exit). Aggregating signed bps over a window reveals whether
 * fills are systematically worse than the model's intended levels — the
 * suspected driver of the −2.84 R per-trade gap between signal model and real
 * P&amp;L.
 *
 * <p>MDC fields: {@code event}, {@code tradeId}, {@code symbol}, {@code direction},
 * {@code intended}, {@code actual}, {@code bps}, plus side-specific {@code side}
 * (entry|exit) and {@code level} (stop|target|trail|entry).
 */
public final class SlippageLogger {

    private static final Logger LOG = Logger.getLogger(SlippageLogger.class);
    private static final int BPS_SCALE = 4;
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private SlippageLogger() {}

    public static void logEntry(ExecutedTrade trade, BigDecimal intended, BigDecimal actual) {
        if (!isUsable(intended) || !isUsable(actual)) return;
        boolean isLong = "LONG".equals(trade.getDirection());
        BigDecimal bps = adverseBps(intended, actual, isLong);
        emit("entry", "entry", trade, intended, actual, bps);
    }

    public static void logExit(ExecutedTrade trade, ExitReason reason,
                                BigDecimal intended, BigDecimal actual) {
        if (!isUsable(intended) || !isUsable(actual)) return;
        boolean isLong = "LONG".equals(trade.getDirection());
        // Exit sign is inverted vs entry: a LONG selling BELOW intended (stop
        // or target) is worse for us; a SHORT covering ABOVE intended is worse.
        BigDecimal bps = adverseBps(intended, actual, !isLong);
        emit("exit", levelLabel(reason), trade, intended, actual, bps);
    }

    private static BigDecimal adverseBps(BigDecimal intended, BigDecimal actual, boolean adverseWhenActualHigher) {
        BigDecimal diff = adverseWhenActualHigher
                ? actual.subtract(intended)
                : intended.subtract(actual);
        return diff.multiply(TEN_THOUSAND)
                .divide(intended.abs(), BPS_SCALE, RoundingMode.HALF_UP);
    }

    private static void emit(String side, String level, ExecutedTrade trade,
                              BigDecimal intended, BigDecimal actual, BigDecimal bps) {
        MDC.put("event", "execution_slippage");
        MDC.put("tradeId", String.valueOf(trade.getId()));
        MDC.put("signalId", String.valueOf(trade.getSignalId()));
        MDC.put("symbol", trade.getSymbol());
        MDC.put("direction", trade.getDirection());
        MDC.put("strategy", String.valueOf(trade.getStrategy()));
        MDC.put("side", side);
        MDC.put("level", level);
        MDC.put("intended", intended.toPlainString());
        MDC.put("actual", actual.toPlainString());
        MDC.put("bps", bps.toPlainString());
        try {
            LOG.infof("execution_slippage side=%s level=%s symbol=%s bps=%s",
                    side, level, trade.getSymbol(), bps.toPlainString());
        } finally {
            MDC.remove("event");
            MDC.remove("tradeId");
            MDC.remove("signalId");
            MDC.remove("symbol");
            MDC.remove("direction");
            MDC.remove("strategy");
            MDC.remove("side");
            MDC.remove("level");
            MDC.remove("intended");
            MDC.remove("actual");
            MDC.remove("bps");
        }
    }

    private static String levelLabel(ExitReason reason) {
        if (reason == null) return "unknown";
        return switch (reason) {
            case INITIAL_STOP -> "initial_stop";
            case TRAIL_STOP -> "trail_stop";
            case TARGET -> "target";
            case STAGNATION -> "stagnation";
            case DONCHIAN_EXIT -> "donchian_exit";
            case MANUAL -> "manual";
            case EXPIRED -> "expired";
            case FLIP_CLOSE -> "flip_close";
            case KILL -> "kill";
            case UNKNOWN -> "unknown";
        };
    }

    private static boolean isUsable(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
