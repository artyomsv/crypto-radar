package com.cryptoradar.signal.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Composite primary key for {@link SignalOutcome}.
 * TimescaleDB hypertables require the time column ({@code firedAt}) to be part
 * of any uniqueness constraint, which is why this is a composite rather than a
 * simple UUID-only key.
 */
public class SignalOutcomeId implements Serializable {

    private Instant firedAt;
    private String signalId;

    public SignalOutcomeId() {
    }

    public SignalOutcomeId(Instant firedAt, String signalId) {
        this.firedAt = firedAt;
        this.signalId = signalId;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public void setFiredAt(Instant firedAt) {
        this.firedAt = firedAt;
    }

    public String getSignalId() {
        return signalId;
    }

    public void setSignalId(String signalId) {
        this.signalId = signalId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignalOutcomeId other)) return false;
        return Objects.equals(firedAt, other.firedAt)
                && Objects.equals(signalId, other.signalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(firedAt, signalId);
    }
}
