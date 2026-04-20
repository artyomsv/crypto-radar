package com.cryptoradar.signal.model;

/**
 * Per-strategy trailing-stop parameters. Travels alongside a {@link TradeSetup}
 * from the detector that emits it to the {@code OutcomeTracker} that persists
 * it, so each tracked trade can carry its own trail configuration.
 *
 * <p>All values are in R-units (multiples of the trade's risk distance). The
 * {@link #DEFAULT} constant matches the parameters shown to be optimal across
 * the full outcome dataset in the initial simulation.
 *
 * @param activationR MFE threshold at which the trail first ratchets from the
 *                    initial stop. Below this, dynamic stop stays unset.
 * @param stepR       Rung size; the trail advances one rung per this much MFE.
 * @param offsetR     Distance (in R) the trail sits behind the current rung.
 */
public record TrailConfig(double activationR, double stepR, double offsetR) {

    /** The all-strategies default. Aggressive capture of "give-back" losses. */
    public static final TrailConfig DEFAULT = new TrailConfig(1.0, 0.5, 0.5);

    public TrailConfig {
        if (activationR <= 0 || stepR <= 0 || offsetR < 0) {
            throw new IllegalArgumentException(
                    "TrailConfig requires activationR>0, stepR>0, offsetR>=0 — got "
                            + activationR + "/" + stepR + "/" + offsetR);
        }
    }
}
