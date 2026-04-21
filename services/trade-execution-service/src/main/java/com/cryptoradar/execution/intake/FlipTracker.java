package com.cryptoradar.execution.intake;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-symbol counter for consecutive same-direction STRONG signals.
 * Emits ENTER/CLOSE actions only after N ticks of persistence.
 *
 * <p>State is lost on restart; first signals after restart are treated as new
 * streak. Acceptable for a 60s evaluator cadence — worst case is a missed
 * flip within the first minute of boot.
 */
@ApplicationScoped
public class FlipTracker {

    public enum Action { NO_ACTION, ENTER_LONG, ENTER_SHORT, CLOSE_LONG, CLOSE_SHORT }

    private record Counter(String lastDirection, int streak) {}

    private final Map<String, Counter> state = new ConcurrentHashMap<>();

    public Action observe(String symbol, String signalLabel, int persistenceTicks,
                          boolean currentlyLong, boolean currentlyShort) {
        String dir = signalToDirection(signalLabel);
        if (dir == null) {
            state.remove(symbol);
            return Action.NO_ACTION;
        }
        Counter prev = state.get(symbol);
        int streak = (prev != null && prev.lastDirection().equals(dir)) ? prev.streak() + 1 : 1;
        state.put(symbol, new Counter(dir, streak));

        if (streak < persistenceTicks) return Action.NO_ACTION;

        if ("LONG".equals(dir)) {
            if (currentlyShort) return Action.CLOSE_SHORT;
            if (!currentlyLong) return Action.ENTER_LONG;
            return Action.NO_ACTION;
        } else {
            if (currentlyLong) return Action.CLOSE_LONG;
            if (!currentlyShort) return Action.ENTER_SHORT;
            return Action.NO_ACTION;
        }
    }

    private String signalToDirection(String signalLabel) {
        return switch (signalLabel) {
            case "BUY", "STRONG_BUY" -> "LONG";
            case "SELL", "STRONG_SELL" -> "SHORT";
            default -> null;
        };
    }
}
