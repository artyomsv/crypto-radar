package com.cryptoradar.options.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One option contract's market state at a point in time. Hypertable —
 * inserts are append-only via {@code OptionSnapshotRepository} batch JDBC.
 *
 * <p>Composite key (time, symbol) matches the hypertable's natural uniqueness
 * for a given second. Hibernate doesn't manage hypertable internals;
 * this entity is read-only at the JPA layer (writes use raw JDBC via
 * AgroalDataSource — same pattern as MarketDataService.upsertCandlesBatch).
 */
@Entity
@Table(name = "option_snapshots")
@IdClass(OptionSnapshot.PK.class)
public class OptionSnapshot {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "symbol", nullable = false, length = 48)
    private String symbol;

    @Column(name = "underlying", nullable = false, length = 16)
    private String underlying;

    @Column(name = "expiry", nullable = false)
    private LocalDate expiry;

    @Column(name = "strike", nullable = false)
    private double strike;

    @Column(name = "option_type", nullable = false, length = 1)
    private String optionType;

    @Column(name = "bid") private Double bid;
    @Column(name = "ask") private Double ask;
    @Column(name = "mark") private Double mark;
    @Column(name = "implied_vol") private Double impliedVol;
    @Column(name = "delta") private Double delta;
    @Column(name = "gamma") private Double gamma;
    @Column(name = "theta") private Double theta;
    @Column(name = "vega") private Double vega;
    @Column(name = "open_interest") private Double openInterest;
    @Column(name = "volume_24h") private Double volume24h;
    @Column(name = "underlying_px") private Double underlyingPx;

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getUnderlying() { return underlying; }
    public void setUnderlying(String underlying) { this.underlying = underlying; }
    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate expiry) { this.expiry = expiry; }
    public double getStrike() { return strike; }
    public void setStrike(double strike) { this.strike = strike; }
    public String getOptionType() { return optionType; }
    public void setOptionType(String optionType) { this.optionType = optionType; }
    public Double getBid() { return bid; }
    public void setBid(Double bid) { this.bid = bid; }
    public Double getAsk() { return ask; }
    public void setAsk(Double ask) { this.ask = ask; }
    public Double getMark() { return mark; }
    public void setMark(Double mark) { this.mark = mark; }
    public Double getImpliedVol() { return impliedVol; }
    public void setImpliedVol(Double impliedVol) { this.impliedVol = impliedVol; }
    public Double getDelta() { return delta; }
    public void setDelta(Double delta) { this.delta = delta; }
    public Double getGamma() { return gamma; }
    public void setGamma(Double gamma) { this.gamma = gamma; }
    public Double getTheta() { return theta; }
    public void setTheta(Double theta) { this.theta = theta; }
    public Double getVega() { return vega; }
    public void setVega(Double vega) { this.vega = vega; }
    public Double getOpenInterest() { return openInterest; }
    public void setOpenInterest(Double openInterest) { this.openInterest = openInterest; }
    public Double getVolume24h() { return volume24h; }
    public void setVolume24h(Double volume24h) { this.volume24h = volume24h; }
    public Double getUnderlyingPx() { return underlyingPx; }
    public void setUnderlyingPx(Double underlyingPx) { this.underlyingPx = underlyingPx; }

    public static class PK implements Serializable {
        private Instant time;
        private String symbol;

        public PK() {}
        public PK(Instant time, String symbol) { this.time = time; this.symbol = symbol; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(time, pk.time) && Objects.equals(symbol, pk.symbol);
        }
        @Override public int hashCode() { return Objects.hash(time, symbol); }
    }
}
