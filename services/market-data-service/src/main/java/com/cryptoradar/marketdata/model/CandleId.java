package com.cryptoradar.marketdata.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public class CandleId implements Serializable {

    private Instant time;
    private String symbol;
    private String interval;

    public CandleId() {
    }

    public CandleId(Instant time, String symbol, String interval) {
        this.time = time;
        this.symbol = symbol;
        this.interval = interval;
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

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CandleId candleId = (CandleId) o;
        return Objects.equals(time, candleId.time)
                && Objects.equals(symbol, candleId.symbol)
                && Objects.equals(interval, candleId.interval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, symbol, interval);
    }
}
