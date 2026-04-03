package com.cryptoradar.marketdata.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "crypto_assets")
public class CryptoAsset {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "name")
    private String name;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "is_active")
    private Boolean isActive;

    public CryptoAsset() {
    }

    public CryptoAsset(String symbol, String name, Integer rank, Boolean isActive) {
        this.symbol = symbol;
        this.name = name;
        this.rank = rank;
        this.isActive = isActive;
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

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
