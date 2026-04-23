package com.cryptoradar.core;

/**
 * Per-strategy trailing-stop parameters in R-units.
 *
 * <p>{@link #DEFAULT} ships Vector F's second-rung: activation at 1R,
 * rung size 0.5R, initial offset 0.5R, and at MFE ≥ 2.5R the offset widens
 * to 1.0R so right-tail runners get more room before the trail takes them.
 *
 * @param activationR           MFE threshold at which the trail first ratchets
 *                              from the initial stop. Below this, dynamic stop
 *                              stays unset.
 * @param stepR                 Rung size; the trail advances one rung per this
 *                              much MFE.
 * @param offsetR               Distance (in R) the trail sits behind the current
 *                              rung while MFE is below the wider-offset
 *                              threshold.
 * @param widerOffsetActivationR MFE threshold (R) at which the wider offset
 *                              kicks in. Set to {@code 0.0} to disable the
 *                              second rung and keep a single offset for life.
 *                              Must be ≥ {@code activationR} when non-zero.
 * @param widerOffsetR          Offset used once MFE crosses
 *                              {@code widerOffsetActivationR}. Typically
 *                              larger than {@code offsetR} to give late
 *                              runners more room.
 */
public record TrailConfig(
        double activationR,
        double stepR,
        double offsetR,
        double widerOffsetActivationR,
        double widerOffsetR) {

    public static final TrailConfig DEFAULT = new TrailConfig(1.0, 0.5, 0.5, 2.5, 1.0);

    // Backward-compatible constructor — single-rung behaviour, disabled
    // wider-offset. Lets existing callers continue to use
    // {@code new TrailConfig(1.0, 0.5, 0.5)} without knowing about the
    // second rung.
    public TrailConfig(double activationR, double stepR, double offsetR) {
        this(activationR, stepR, offsetR, 0.0, 0.0);
    }

    public TrailConfig {
        if (activationR <= 0 || stepR <= 0 || offsetR < 0) {
            throw new IllegalArgumentException(
                    "TrailConfig requires activationR>0, stepR>0, offsetR>=0 — got "
                            + activationR + "/" + stepR + "/" + offsetR);
        }
        if (widerOffsetActivationR < 0 || widerOffsetR < 0) {
            throw new IllegalArgumentException(
                    "widerOffsetActivationR and widerOffsetR must be >= 0 — got "
                            + widerOffsetActivationR + "/" + widerOffsetR);
        }
        if (widerOffsetActivationR > 0 && widerOffsetActivationR < activationR) {
            throw new IllegalArgumentException(
                    "widerOffsetActivationR (" + widerOffsetActivationR
                            + ") must be >= activationR (" + activationR + ")");
        }
    }
}
