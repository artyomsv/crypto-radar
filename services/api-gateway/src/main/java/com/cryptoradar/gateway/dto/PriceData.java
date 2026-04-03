package com.cryptoradar.gateway.dto;

import java.util.List;

public class PriceData {

    private String symbol;
    private String name;
    private Double price;
    private Double priceChange24h;
    private Double priceChangePct24h;
    private Double volume24h;
    private Double marketCap;
    private List<Double> sparkline;

    public PriceData() {
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getPriceChange24h() {
        return priceChange24h;
    }

    public void setPriceChange24h(Double priceChange24h) {
        this.priceChange24h = priceChange24h;
    }

    public Double getPriceChangePct24h() {
        return priceChangePct24h;
    }

    public void setPriceChangePct24h(Double priceChangePct24h) {
        this.priceChangePct24h = priceChangePct24h;
    }

    public Double getVolume24h() {
        return volume24h;
    }

    public void setVolume24h(Double volume24h) {
        this.volume24h = volume24h;
    }

    public Double getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(Double marketCap) {
        this.marketCap = marketCap;
    }

    public List<Double> getSparkline() {
        return sparkline;
    }

    public void setSparkline(List<Double> sparkline) {
        this.sparkline = sparkline;
    }
}
