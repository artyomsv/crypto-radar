package com.cryptoradar.news.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class DailySentimentId implements Serializable {

    private LocalDate date;
    private String symbol;

    public DailySentimentId() {
    }

    public DailySentimentId(LocalDate date, String symbol) {
        this.date = date;
        this.symbol = symbol;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
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
        DailySentimentId that = (DailySentimentId) o;
        return Objects.equals(date, that.date) && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, symbol);
    }
}
