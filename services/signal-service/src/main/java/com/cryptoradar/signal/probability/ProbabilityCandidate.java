package com.cryptoradar.signal.probability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A shadow trade candidate produced by the hourly probability scan, with its
 * predicted probabilities and (once forward-evaluated) its realized outcome.
 * Nothing here ever places a live order — these rows exist to measure whether
 * the probabilities are calibrated before the gate is trusted.
 */
@Entity
@Table(name = "probability_candidates")
public class ProbabilityCandidate {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_HIT_TARGET = "HIT_TARGET";
    public static final String STATUS_HIT_STOP = "HIT_STOP";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "scanned_at", nullable = false)
    public Instant scannedAt;

    @Column(nullable = false)
    public String symbol;

    @Column(nullable = false)
    public String direction;

    @Column(name = "entry_price", nullable = false)
    public double entryPrice;

    @Column(name = "stop_price", nullable = false)
    public double stopPrice;

    @Column(name = "target_price", nullable = false)
    public double targetPrice;

    @Column(nullable = false)
    public double atr;

    @Column(name = "risk_reward", nullable = false)
    public double riskReward;

    /** Calibrated logistic-model probability of hitting target before stop. */
    @Column(name = "stats_prob")
    public Double statsProb;

    /** LLM-estimated probability (nullable — fail-open when the LLM is unavailable). */
    @Column(name = "llm_prob")
    public Double llmProb;

    @Column(name = "llm_reasoning", columnDefinition = "TEXT")
    public String llmReasoning;

    /** JSON snapshot of the full feature vector, for future rich-model training. */
    @Column(name = "features_json", columnDefinition = "TEXT")
    public String featuresJson;

    @Column(nullable = false)
    public String status = STATUS_PENDING;

    @Column(name = "closed_at")
    public Instant closedAt;

    @Column(name = "closed_price")
    public Double closedPrice;

    /** Generator config that produced this candidate (geometry + direction policy). */
    @Column(name = "config_tag")
    public String configTag;

    /** Max favorable / adverse excursion over the candidate's life, in ATR units. */
    @Column(name = "mfe_atr")
    public Double mfeAtr;

    @Column(name = "mae_atr")
    public Double maeAtr;

    /** Empirically recalibrated win probability (from {@code ProbabilityCalibrator}). */
    @Column(name = "calibrated_prob")
    public Double calibratedProb;
}
