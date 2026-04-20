package com.cryptoradar.core;

/**
 * Per-strategy trailing-stop parameters in R-units.
 *
 * <p>{@link #DEFAULT} matches the parameters optimal across the full outcome
 * dataset in the initial simulation: activation at 1R, rung size 0.5R, trail
 * sits 0.5R behind the current rung.
 *
 * @param activationR MFE threshold at which the trail first ratchets from the
 *                    initial stop. Below this, dynamic stop stays unset.
 * @param stepR       Rung size; the trail advances one rung per this much MFE.
 * @param offsetR     Distance (in R) the trail sits behind the current rung.
 */
public record TrailConfig(double activationR, double stepR, double offsetR) {

    public static final TrailConfig DEFAULT = new TrailConfig(1.0, 0.5, 0.5);

    public TrailConfig {
        if (activationR <= 0 || stepR <= 0 || offsetR < 0) {
            throw new IllegalArgumentException(
                    "TrailConfig requires activationR>0, stepR>0, offsetR>=0 — got "
                            + activationR + "/" + stepR + "/" + offsetR);
        }
    }
}
