package com.cryptoradar.analytics.model;

public class VolatilityMetric {

    private String symbol;
    private Double atrPct;
    private Double bollingerWidth;
    private Double range24hPct;
    private int volatilityRank;

    public VolatilityMetric() {
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Double getAtrPct() {
        return atrPct;
    }

    public void setAtrPct(Double atrPct) {
        this.atrPct = atrPct;
    }

    public Double getBollingerWidth() {
        return bollingerWidth;
    }

    public void setBollingerWidth(Double bollingerWidth) {
        this.bollingerWidth = bollingerWidth;
    }

    public Double getRange24hPct() {
        return range24hPct;
    }

    public void setRange24hPct(Double range24hPct) {
        this.range24hPct = range24hPct;
    }

    public int getVolatilityRank() {
        return volatilityRank;
    }

    public void setVolatilityRank(int volatilityRank) {
        this.volatilityRank = volatilityRank;
    }
}
