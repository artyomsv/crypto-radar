package com.cryptoradar.signal.model;

/**
 * Lifecycle state of a tracked signal outcome.
 *
 * <ul>
 *   <li>{@link #PENDING}      — still open, neither stop nor target hit yet.</li>
 *   <li>{@link #HIT_TARGET}   — price reached {@code targetPrice} before {@code stopPrice}.</li>
 *   <li>{@link #HIT_STOP}     — price reached {@code stopPrice} before {@code targetPrice}.</li>
 *   <li>{@link #EXPIRED}      — neither hit within the max hold window; closed at last price.</li>
 * </ul>
 */
public enum OutcomeStatus {
    PENDING,
    HIT_TARGET,
    HIT_STOP,
    EXPIRED
}
