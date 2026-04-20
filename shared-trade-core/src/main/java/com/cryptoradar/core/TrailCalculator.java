package com.cryptoradar.core;

import java.util.Optional;

/**
 * Pure trailing-stop rung math. Given MFE progress, config, and current rung,
 * returns the new rung — or empty if no advance.
 *
 * <p>Algorithm (matches the spec trail ladder, R-units throughout):
 * <pre>
 *   if mfeR &lt; activationR → no advance
 *   rung      = floor((mfeR - activationR) / stepR)
 *   newTrailR = activationR + rung * stepR - offsetR
 *   if newTrailR &lt;= currentHighestR → no advance (monotonic)
 *   else → advance to newTrailR
 * </pre>
 *
 * <p>Monotonic: never loosens when price pulls back below the current rung.
 * Translating newTrailR to a concrete price is the caller's job (they know
 * direction and entry).
 */
public final class TrailCalculator {

    private TrailCalculator() {}

    public static Optional<Double> computeNewTrailR(double mfeR, TrailConfig config, double currentHighestR) {
        if (mfeR < config.activationR()) {
            return Optional.empty();
        }
        double rung = Math.floor((mfeR - config.activationR()) / config.stepR());
        double newTrailR = config.activationR() + rung * config.stepR() - config.offsetR();
        if (newTrailR <= currentHighestR) {
            return Optional.empty();
        }
        return Optional.of(newTrailR);
    }
}
