package com.cryptoradar.signal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persisted record of a signal fired by the engine, tracked until it hits
 * stop, target, or expires.
 *
 * <p>Created by {@code OutcomeTracker} when a signal transitions from neutral
 * to actionable. Updated by {@code OutcomeEvaluator} every minute with the
 * latest evaluation against 1m candles until it closes.
 */
@Entity
@Table(name = "signal_outcomes")
@IdClass(SignalOutcomeId.class)
public class SignalOutcome {

    @Id
    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @Id
    @Column(name = "signal_id", nullable = false, length = 64)
    private String signalId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "strategy", nullable = false, length = 32)
    private String strategy = "dimension-scoring";

    @Column(name = "signal_type", nullable = false, length = 16)
    private String signalType;

    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    @Column(name = "entry_price", nullable = false)
    private Double entryPrice;

    @Column(name = "stop_price", nullable = false)
    private Double stopPrice;

    @Column(name = "target_price", nullable = false)
    private Double targetPrice;

    @Column(name = "risk_reward_ratio", nullable = false)
    private Double riskRewardRatio;

    @Column(name = "confidence", nullable = false)
    private Integer confidence;

    @Column(name = "overall_score", nullable = false)
    private Double overallScore;

    @Column(name = "technical_score")
    private Double technicalScore;

    @Column(name = "whale_score")
    private Double whaleScore;

    @Column(name = "derivatives_score")
    private Double derivativesScore;

    @Column(name = "sentiment_score")
    private Double sentimentScore;

    @Column(name = "orderbook_score")
    private Double orderbookScore;

    @Column(name = "macro_score")
    private Double macroScore;

    @Column(name = "ai_analysis", columnDefinition = "TEXT")
    private String aiAnalysis;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutcomeStatus status = OutcomeStatus.PENDING;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_price")
    private Double closedPrice;

    @Column(name = "realized_pnl_pct")
    private Double realizedPnlPct;

    @Column(name = "realized_r_multiple")
    private Double realizedRMultiple;

    @Column(name = "max_favorable_pct", nullable = false)
    private Double maxFavorablePct = 0.0;

    @Column(name = "max_adverse_pct", nullable = false)
    private Double maxAdversePct = 0.0;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    public SignalOutcome() {
    }

    public Instant getFiredAt() { return firedAt; }
    public void setFiredAt(Instant firedAt) { this.firedAt = firedAt; }

    public String getSignalId() { return signalId; }
    public void setSignalId(String signalId) { this.signalId = signalId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public Double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(Double entryPrice) { this.entryPrice = entryPrice; }

    public Double getStopPrice() { return stopPrice; }
    public void setStopPrice(Double stopPrice) { this.stopPrice = stopPrice; }

    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }

    public Double getRiskRewardRatio() { return riskRewardRatio; }
    public void setRiskRewardRatio(Double riskRewardRatio) { this.riskRewardRatio = riskRewardRatio; }

    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }

    public Double getOverallScore() { return overallScore; }
    public void setOverallScore(Double overallScore) { this.overallScore = overallScore; }

    public Double getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(Double technicalScore) { this.technicalScore = technicalScore; }

    public Double getWhaleScore() { return whaleScore; }
    public void setWhaleScore(Double whaleScore) { this.whaleScore = whaleScore; }

    public Double getDerivativesScore() { return derivativesScore; }
    public void setDerivativesScore(Double derivativesScore) { this.derivativesScore = derivativesScore; }

    public Double getSentimentScore() { return sentimentScore; }
    public void setSentimentScore(Double sentimentScore) { this.sentimentScore = sentimentScore; }

    public Double getOrderbookScore() { return orderbookScore; }
    public void setOrderbookScore(Double orderbookScore) { this.orderbookScore = orderbookScore; }

    public Double getMacroScore() { return macroScore; }
    public void setMacroScore(Double macroScore) { this.macroScore = macroScore; }

    public String getAiAnalysis() { return aiAnalysis; }
    public void setAiAnalysis(String aiAnalysis) { this.aiAnalysis = aiAnalysis; }

    public OutcomeStatus getStatus() { return status; }
    public void setStatus(OutcomeStatus status) { this.status = status; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public Double getClosedPrice() { return closedPrice; }
    public void setClosedPrice(Double closedPrice) { this.closedPrice = closedPrice; }

    public Double getRealizedPnlPct() { return realizedPnlPct; }
    public void setRealizedPnlPct(Double realizedPnlPct) { this.realizedPnlPct = realizedPnlPct; }

    public Double getRealizedRMultiple() { return realizedRMultiple; }
    public void setRealizedRMultiple(Double realizedRMultiple) { this.realizedRMultiple = realizedRMultiple; }

    public Double getMaxFavorablePct() { return maxFavorablePct; }
    public void setMaxFavorablePct(Double maxFavorablePct) { this.maxFavorablePct = maxFavorablePct; }

    public Double getMaxAdversePct() { return maxAdversePct; }
    public void setMaxAdversePct(Double maxAdversePct) { this.maxAdversePct = maxAdversePct; }

    public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
    public void setLastEvaluatedAt(Instant lastEvaluatedAt) { this.lastEvaluatedAt = lastEvaluatedAt; }
}
