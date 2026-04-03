package com.cryptoradar.analytics.model;

import java.time.Instant;

public class TechnicalIndicators {

    private String symbol;
    private Instant timestamp;
    private Double rsi14;
    private Double macdLine;
    private Double macdSignal;
    private Double macdHistogram;
    private Double bollingerUpper;
    private Double bollingerMiddle;
    private Double bollingerLower;
    private Double ema12;
    private Double ema26;
    private Double sma20;
    private Double sma50;
    private Double sma200;
    private Double atr14;

    public TechnicalIndicators() {
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Double getRsi14() {
        return rsi14;
    }

    public void setRsi14(Double rsi14) {
        this.rsi14 = rsi14;
    }

    public Double getMacdLine() {
        return macdLine;
    }

    public void setMacdLine(Double macdLine) {
        this.macdLine = macdLine;
    }

    public Double getMacdSignal() {
        return macdSignal;
    }

    public void setMacdSignal(Double macdSignal) {
        this.macdSignal = macdSignal;
    }

    public Double getMacdHistogram() {
        return macdHistogram;
    }

    public void setMacdHistogram(Double macdHistogram) {
        this.macdHistogram = macdHistogram;
    }

    public Double getBollingerUpper() {
        return bollingerUpper;
    }

    public void setBollingerUpper(Double bollingerUpper) {
        this.bollingerUpper = bollingerUpper;
    }

    public Double getBollingerMiddle() {
        return bollingerMiddle;
    }

    public void setBollingerMiddle(Double bollingerMiddle) {
        this.bollingerMiddle = bollingerMiddle;
    }

    public Double getBollingerLower() {
        return bollingerLower;
    }

    public void setBollingerLower(Double bollingerLower) {
        this.bollingerLower = bollingerLower;
    }

    public Double getEma12() {
        return ema12;
    }

    public void setEma12(Double ema12) {
        this.ema12 = ema12;
    }

    public Double getEma26() {
        return ema26;
    }

    public void setEma26(Double ema26) {
        this.ema26 = ema26;
    }

    public Double getSma20() {
        return sma20;
    }

    public void setSma20(Double sma20) {
        this.sma20 = sma20;
    }

    public Double getSma50() {
        return sma50;
    }

    public void setSma50(Double sma50) {
        this.sma50 = sma50;
    }

    public Double getSma200() {
        return sma200;
    }

    public void setSma200(Double sma200) {
        this.sma200 = sma200;
    }

    public Double getAtr14() {
        return atr14;
    }

    public void setAtr14(Double atr14) {
        this.atr14 = atr14;
    }
}
