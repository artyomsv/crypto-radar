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
 * One detected short-vol opportunity (IV substantially above RV). Mirrors
 * {@link OptionOpportunity} structurally but carries the defined-risk
 * structure descriptor (iron condor / credit spread) instead of a single
 * strangle. Persisted by {@code ShortVolOpportunityScorer}.
 *
 * <p>Tier 4 execution reads this table to pick orders. Until then it is
 * alert-only — rows exist but no Bybit orders are placed.
 */
@Entity
@Table(name = "option_short_vol_opportunities")
public class OptionShortVolOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(nullable = false, length = 16)
    private String underlying;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(name = "structure_type", nullable = false, length = 24)
    private String structureType;

    @Column(name = "short_call_symbol", length = 48)
    private String shortCallSymbol;
    @Column(name = "short_call_strike")
    private Double shortCallStrike;
    @Column(name = "short_call_delta")
    private Double shortCallDelta;
    @Column(name = "short_put_symbol", length = 48)
    private String shortPutSymbol;
    @Column(name = "short_put_strike")
    private Double shortPutStrike;
    @Column(name = "short_put_delta")
    private Double shortPutDelta;

    @Column(name = "long_call_symbol", length = 48)
    private String longCallSymbol;
    @Column(name = "long_call_strike")
    private Double longCallStrike;
    @Column(name = "long_put_symbol", length = 48)
    private String longPutSymbol;
    @Column(name = "long_put_strike")
    private Double longPutStrike;

    @Column(name = "net_credit", nullable = false)
    private Double netCredit;

    @Column(name = "max_loss_usd", nullable = false)
    private Double maxLossUsd;

    @Column(name = "pop_pct")
    private Double popPct;

    @Column(name = "break_even_low")
    private Double breakEvenLow;

    @Column(name = "break_even_high")
    private Double breakEvenHigh;

    @Column(name = "implied_vol_atm")
    private Double impliedVolAtm;

    @Column(name = "realized_vol_14d")
    private Double realizedVol14d;

    @Column(name = "iv_rv_premium_pct")
    private Double ivRvPremiumPct;

    @Column(name = "term_structure_score")
    private Double termStructureScore;

    @Column(name = "signal_quiet_score")
    private Double signalQuietScore;

    @Column(name = "iv_percentile_score")
    private Double ivPercentileScore;

    @Column(nullable = false)
    private Double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "outcome_label", length = 16)
    private String outcomeLabel;

    @Column(name = "outcome_pnl_usd")
    private Double outcomePnlUsd;

    @Column(name = "outcome_pnl_pct")
    private Double outcomePnlPct;

    @Column(name = "outcome_resolved_at")
    private Instant outcomeResolvedAt;

    // Getters / setters — verbose but unavoidable for JPA. Pattern matches
    // OptionOpportunity in the same package.
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant v) { this.detectedAt = v; }
    public String getUnderlying() { return underlying; }
    public void setUnderlying(String v) { this.underlying = v; }
    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate v) { this.expiry = v; }
    public String getStructureType() { return structureType; }
    public void setStructureType(String v) { this.structureType = v; }
    public String getShortCallSymbol() { return shortCallSymbol; }
    public void setShortCallSymbol(String v) { this.shortCallSymbol = v; }
    public Double getShortCallStrike() { return shortCallStrike; }
    public void setShortCallStrike(Double v) { this.shortCallStrike = v; }
    public Double getShortCallDelta() { return shortCallDelta; }
    public void setShortCallDelta(Double v) { this.shortCallDelta = v; }
    public String getShortPutSymbol() { return shortPutSymbol; }
    public void setShortPutSymbol(String v) { this.shortPutSymbol = v; }
    public Double getShortPutStrike() { return shortPutStrike; }
    public void setShortPutStrike(Double v) { this.shortPutStrike = v; }
    public Double getShortPutDelta() { return shortPutDelta; }
    public void setShortPutDelta(Double v) { this.shortPutDelta = v; }
    public String getLongCallSymbol() { return longCallSymbol; }
    public void setLongCallSymbol(String v) { this.longCallSymbol = v; }
    public Double getLongCallStrike() { return longCallStrike; }
    public void setLongCallStrike(Double v) { this.longCallStrike = v; }
    public String getLongPutSymbol() { return longPutSymbol; }
    public void setLongPutSymbol(String v) { this.longPutSymbol = v; }
    public Double getLongPutStrike() { return longPutStrike; }
    public void setLongPutStrike(Double v) { this.longPutStrike = v; }
    public Double getNetCredit() { return netCredit; }
    public void setNetCredit(Double v) { this.netCredit = v; }
    public Double getMaxLossUsd() { return maxLossUsd; }
    public void setMaxLossUsd(Double v) { this.maxLossUsd = v; }
    public Double getPopPct() { return popPct; }
    public void setPopPct(Double v) { this.popPct = v; }
    public Double getBreakEvenLow() { return breakEvenLow; }
    public void setBreakEvenLow(Double v) { this.breakEvenLow = v; }
    public Double getBreakEvenHigh() { return breakEvenHigh; }
    public void setBreakEvenHigh(Double v) { this.breakEvenHigh = v; }
    public Double getImpliedVolAtm() { return impliedVolAtm; }
    public void setImpliedVolAtm(Double v) { this.impliedVolAtm = v; }
    public Double getRealizedVol14d() { return realizedVol14d; }
    public void setRealizedVol14d(Double v) { this.realizedVol14d = v; }
    public Double getIvRvPremiumPct() { return ivRvPremiumPct; }
    public void setIvRvPremiumPct(Double v) { this.ivRvPremiumPct = v; }
    public Double getTermStructureScore() { return termStructureScore; }
    public void setTermStructureScore(Double v) { this.termStructureScore = v; }
    public Double getSignalQuietScore() { return signalQuietScore; }
    public void setSignalQuietScore(Double v) { this.signalQuietScore = v; }
    public Double getIvPercentileScore() { return ivPercentileScore; }
    public void setIvPercentileScore(Double v) { this.ivPercentileScore = v; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double v) { this.confidence = v; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> v) { this.metadata = v; }
    public String getOutcomeLabel() { return outcomeLabel; }
    public void setOutcomeLabel(String v) { this.outcomeLabel = v; }
    public Double getOutcomePnlUsd() { return outcomePnlUsd; }
    public void setOutcomePnlUsd(Double v) { this.outcomePnlUsd = v; }
    public Double getOutcomePnlPct() { return outcomePnlPct; }
    public void setOutcomePnlPct(Double v) { this.outcomePnlPct = v; }
    public Instant getOutcomeResolvedAt() { return outcomeResolvedAt; }
    public void setOutcomeResolvedAt(Instant v) { this.outcomeResolvedAt = v; }
}
