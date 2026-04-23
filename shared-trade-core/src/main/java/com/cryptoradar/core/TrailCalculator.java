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

    /**
     * @param mfeR            cumulative Maximum Favorable Excursion in R-units
     * @param config          trail parameters (activationR, stepR, offsetR)
     * @param currentHighestR the highest rung the trail has previously reached
     *                        (0.0 if the trail has never advanced)
     * @return {@code Optional.of(newTrailR)} when the trail should advance,
     *         {@code Optional.empty()} when MFE is below activation or the
     *         computed rung does not exceed {@code currentHighestR}
     */
    public static Optional<Double> computeNewTrailR(double mfeR, TrailConfig config, double currentHighestR) {
        if (mfeR < config.activationR()) {
            return Optional.empty();
        }
        double rung = Math.floor((mfeR - config.activationR()) / config.stepR());
        double effectiveOffsetR = resolveOffset(mfeR, config);
        double newTrailR = config.activationR() + rung * config.stepR() - effectiveOffsetR;
        if (newTrailR <= currentHighestR) {
            return Optional.empty();
        }
        return Optional.of(newTrailR);
    }

    /**
     * Vector F — trail offset widens in the right tail. Below the
     * {@code widerOffsetActivationR} threshold the trail uses the tight
     * {@code offsetR}; once MFE crosses, the looser {@code widerOffsetR}
     * applies so late runners have more room before the trail takes them.
     * When {@code widerOffsetActivationR <= 0} the second rung is disabled
     * and this collapses to the single-offset ladder.
     */
    private static double resolveOffset(double mfeR, TrailConfig config) {
        if (config.widerOffsetActivationR() <= 0) {
            return config.offsetR();
        }
        return mfeR >= config.widerOffsetActivationR()
                ? config.widerOffsetR()
                : config.offsetR();
    }
}
