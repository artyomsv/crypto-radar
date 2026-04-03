package com.cryptoradar.derivatives.model;

import java.time.Instant;

public class OpenInterest {

    private String symbol;
    private double openInterest;
    private double openInterestValue;
    private Instant timestamp;

    public OpenInterest() {}

    public OpenInterest(String symbol, double openInterest, double openInterestValue, Instant timestamp) {
        this.symbol = symbol;
        this.openInterest = openInterest;
        this.openInterestValue = openInterestValue;
        this.timestamp = timestamp;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public double getOpenInterest() { return openInterest; }
    public void setOpenInterest(double openInterest) { this.openInterest = openInterest; }

    public double getOpenInterestValue() { return openInterestValue; }
    public void setOpenInterestValue(double openInterestValue) { this.openInterestValue = openInterestValue; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
