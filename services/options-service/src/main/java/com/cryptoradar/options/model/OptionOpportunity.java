package com.cryptoradar.options.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * One scored strangle/straddle opportunity. Insert-only via the scorer; the
 * ground-truth fields (realized_move_pct, outcome_pnl_pct, outcome_resolved_at)
 * are filled by a future backfill job once the expiry has passed.
 */
@Entity
@Table(name = "option_opportunities")
public class OptionOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "underlying", nullable = false, length = 16)
    private String underlying;

    @Column(name = "expiry", nullable = false)
    private LocalDate expiry;

    @Column(name = "strike_call", nullable = false)
    private double strikeCall;

    @Column(name = "strike_put", nullable = false)
    private double strikePut;

    @Column(name = "call_symbol", nullable = false, length = 48)
    private String callSymbol;

    @Column(name = "put_symbol", nullable = false, length = 48)
    private String putSymbol;

    @Column(name = "strangle_premium", nullable = false)
    private double stranglePremium;

    @Column(name = "implied_vol_atm")
    private Double impliedVolAtm;

    @Column(name = "realized_vol_7d")
    private Double realizedVol7d;

    @Column(name = "realized_vol_14d")
    private Double realizedVol14d;

    @Column(name = "iv_rv_spread")
    private Double ivRvSpread;

    @Column(name = "signal_overlay")
    private Double signalOverlay;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "realized_move_pct")
    private Double realizedMovePct;

    @Column(name = "outcome_pnl_pct")
    private Double outcomePnlPct;

    @Column(name = "outcome_resolved_at")
    private Instant outcomeResolvedAt;

    // Tier 3 triple-barrier label (WIN / LOSS / EXPIRED) — column added by
    // options-shortvol-init.sql migration. Writer is TripleBarrierResolver.
    @Column(name = "outcome_label", length = 16)
    private String outcomeLabel;

    public Long getId() { return id; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public String getUnderlying() { return underlying; }
    public void setUnderlying(String underlying) { this.underlying = underlying; }
    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate expiry) { this.expiry = expiry; }
    public double getStrikeCall() { return strikeCall; }
    public void setStrikeCall(double strikeCall) { this.strikeCall = strikeCall; }
    public double getStrikePut() { return strikePut; }
    public void setStrikePut(double strikePut) { this.strikePut = strikePut; }
    public String getCallSymbol() { return callSymbol; }
    public void setCallSymbol(String callSymbol) { this.callSymbol = callSymbol; }
    public String getPutSymbol() { return putSymbol; }
    public void setPutSymbol(String putSymbol) { this.putSymbol = putSymbol; }
    public double getStranglePremium() { return stranglePremium; }
    public void setStranglePremium(double stranglePremium) { this.stranglePremium = stranglePremium; }
    public Double getImpliedVolAtm() { return impliedVolAtm; }
    public void setImpliedVolAtm(Double impliedVolAtm) { this.impliedVolAtm = impliedVolAtm; }
    public Double getRealizedVol7d() { return realizedVol7d; }
    public void setRealizedVol7d(Double v) { this.realizedVol7d = v; }
    public Double getRealizedVol14d() { return realizedVol14d; }
    public void setRealizedVol14d(Double v) { this.realizedVol14d = v; }
    public Double getIvRvSpread() { return ivRvSpread; }
    public void setIvRvSpread(Double v) { this.ivRvSpread = v; }
    public Double getSignalOverlay() { return signalOverlay; }
    public void setSignalOverlay(Double v) { this.signalOverlay = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Double getRealizedMovePct() { return realizedMovePct; }
    public void setRealizedMovePct(Double v) { this.realizedMovePct = v; }
    public Double getOutcomePnlPct() { return outcomePnlPct; }
    public void setOutcomePnlPct(Double v) { this.outcomePnlPct = v; }
    public Instant getOutcomeResolvedAt() { return outcomeResolvedAt; }
    public void setOutcomeResolvedAt(Instant v) { this.outcomeResolvedAt = v; }
    public String getOutcomeLabel() { return outcomeLabel; }
    public void setOutcomeLabel(String v) { this.outcomeLabel = v; }
}
