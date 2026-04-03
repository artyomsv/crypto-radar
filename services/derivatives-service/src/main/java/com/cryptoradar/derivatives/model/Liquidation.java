package com.cryptoradar.derivatives.model;

import java.time.Instant;

public class Liquidation {

    private String symbol;
    private String side;
    private double price;
    private double quantity;
    private double valueUsd;
    private Instant time;

    public Liquidation() {}

    public Liquidation(String symbol, String side, double price, double quantity,
                       double valueUsd, Instant time) {
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.valueUsd = valueUsd;
        this.time = time;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public double getQuantity() { return quantity; }
    public void setQuantity(double quantity) { this.quantity = quantity; }

    public double getValueUsd() { return valueUsd; }
    public void setValueUsd(double valueUsd) { this.valueUsd = valueUsd; }

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }
}
