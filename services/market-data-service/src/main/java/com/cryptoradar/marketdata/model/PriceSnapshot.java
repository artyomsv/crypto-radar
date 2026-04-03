package com.cryptoradar.marketdata.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "price_snapshots")
@IdClass(PriceSnapshotId.class)
public class PriceSnapshot {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "price")
    private Double price;

    @Column(name = "price_change_24h")
    private Double priceChange24h;

    @Column(name = "price_change_pct_24h")
    private Double priceChangePct24h;

    @Column(name = "volume_24h")
    private Double volume24h;

    @Column(name = "market_cap")
    private Double marketCap;

    public PriceSnapshot() {
    }

    public PriceSnapshot(Instant time, String symbol, Double price, Double priceChange24h,
                         Double priceChangePct24h, Double volume24h, Double marketCap) {
        this.time = time;
        this.symbol = symbol;
        this.price = price;
        this.priceChange24h = priceChange24h;
        this.priceChangePct24h = priceChangePct24h;
        this.volume24h = volume24h;
        this.marketCap = marketCap;
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
}
