package com.cryptoradar.signal.probability;

/**
 * A proposed trade with deterministic ATR-based geometry, before any
 * probability is attached. Pure value object.
 *
 * @param direction "LONG" or "SHORT"
 * @param entry     entry price (current price at scan time)
 * @param stop      stop price (entry ∓ k×ATR)
 * @param target    target price (entry ± R×risk)
 * @param atr       the ATR used to build the geometry
 */
public record Candidate(String direction, double entry, double stop, double target, double atr) {

    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";

    /** Absolute risk per unit (distance from entry to stop). */
    public double risk() {
        return Math.abs(entry - stop);
    }

    /** Absolute reward per unit (distance from entry to target). */
    public double reward() {
        return Math.abs(target - entry);
    }

    /** Reward-to-risk ratio; 0 when risk is degenerate. */
    public double riskReward() {
        double risk = risk();
        return risk <= 0 ? 0.0 : reward() / risk;
    }

    public boolean isLong() {
        return LONG.equals(direction);
    }
}
