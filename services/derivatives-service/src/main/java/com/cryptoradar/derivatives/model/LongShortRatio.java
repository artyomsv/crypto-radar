package com.cryptoradar.derivatives.model;

import java.time.Instant;

public class LongShortRatio {

    private String symbol;
    private double longAccount;
    private double shortAccount;
    private double longShortRatio;
    private Instant timestamp;

    public LongShortRatio() {}

    public LongShortRatio(String symbol, double longAccount, double shortAccount,
                          double longShortRatio, Instant timestamp) {
        this.symbol = symbol;
        this.longAccount = longAccount;
        this.shortAccount = shortAccount;
        this.longShortRatio = longShortRatio;
        this.timestamp = timestamp;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public double getLongAccount() { return longAccount; }
    public void setLongAccount(double longAccount) { this.longAccount = longAccount; }

    public double getShortAccount() { return shortAccount; }
    public void setShortAccount(double shortAccount) { this.shortAccount = shortAccount; }

    public double getLongShortRatio() { return longShortRatio; }
    public void setLongShortRatio(double longShortRatio) { this.longShortRatio = longShortRatio; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
