package com.cryptoradar.marketdata.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public class PriceSnapshotId implements Serializable {

    private Instant time;
    private String symbol;

    public PriceSnapshotId() {
    }

    public PriceSnapshotId(Instant time, String symbol) {
        this.time = time;
        this.symbol = symbol;
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PriceSnapshotId that = (PriceSnapshotId) o;
        return Objects.equals(time, that.time)
                && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, symbol);
    }
}
