package com.cryptoradar.signal.probability;

/**
 * Builds a {@link Candidate} with deterministic ATR-based geometry for a given
 * direction. Pure — no I/O, no state. Stop sits k×ATR from entry; target is a
 * fixed R-multiple of the (volatility-derived) risk. The risk distance is
 * floored at {@link #MIN_RISK_PCT} of entry to stay above round-trip fee drag,
 * matching the convention enforced elsewhere in the engine.
 */
public final class CandidateBuilder {

    /** Minimum risk distance as a fraction of entry (1.5%). */
    public static final double MIN_RISK_PCT = 0.015;

    private CandidateBuilder() {}

    /**
     * @param stopAtrMult stop distance in ATRs
     * @param targetR     target distance as a multiple of risk (reward:risk)
     */
    public static Candidate build(String direction, double entry, double atr,
                                  double stopAtrMult, double targetR) {
        if (entry <= 0 || atr <= 0) {
            throw new IllegalArgumentException(
                    "entry and atr must be positive: entry=" + entry + " atr=" + atr);
        }
        double risk = Math.max(stopAtrMult * atr, MIN_RISK_PCT * entry);
        double reward = targetR * risk;
        boolean isLong = Candidate.LONG.equals(direction);
        if (!isLong && !Candidate.SHORT.equals(direction)) {
            throw new IllegalArgumentException("direction must be LONG or SHORT: " + direction);
        }
        double stop = isLong ? entry - risk : entry + risk;
        double target = isLong ? entry + reward : entry - reward;
        return new Candidate(direction, entry, stop, target, atr);
    }
}
