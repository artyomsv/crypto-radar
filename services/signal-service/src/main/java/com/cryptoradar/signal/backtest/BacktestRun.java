package com.cryptoradar.signal.backtest;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * Aggregate result of one Tier 1 backtest run.
 *
 * <p>Tier 1 replays stored {@code signal_outcomes} against a proposed
 * {@code SignalConfig}, asking: "if the engine had used this config, which
 * signals would it have emitted, and what would the P&amp;L have been?"
 */
@Entity
@Table(name = "backtest_runs")
public class BacktestRun extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "config_version_id", nullable = false)
    public Long configVersionId;

    @Column(name = "period_start", nullable = false)
    public Instant periodStart;

    @Column(name = "period_end", nullable = false)
    public Instant periodEnd;

    @Column(name = "tier", nullable = false)
    public short tier = 1;

    @Column(name = "trade_count", nullable = false)
    public int tradeCount = 0;

    @Column(name = "win_count", nullable = false)
    public int winCount = 0;

    @Column(name = "loss_count", nullable = false)
    public int lossCount = 0;

    @Column(name = "total_r", nullable = false)
    public double totalR = 0.0;

    @Column(name = "avg_r", nullable = false)
    public double avgR = 0.0;

    @Column(name = "avg_winner_r")
    public Double avgWinnerR;

    @Column(name = "avg_loser_r")
    public Double avgLoserR;

    @Column(name = "original_trade_count", nullable = false)
    public int originalTradeCount = 0;

    @Column(name = "original_total_r", nullable = false)
    public double originalTotalR = 0.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    public String metadata;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "duration_ms", nullable = false)
    public int durationMs = 0;
}
