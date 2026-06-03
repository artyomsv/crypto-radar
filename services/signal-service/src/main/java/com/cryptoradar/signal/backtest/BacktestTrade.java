package com.cryptoradar.signal.backtest;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-trade detail row for a {@link BacktestRun}.
 *
 * <p>Lets the UI compare the original signal the engine emitted against what
 * the proposed config would have emitted, and show the realized R impact of
 * each divergence. Cascades on run delete via the FK constraint in SQL.
 */
@Entity
@Table(name = "backtest_trades")
public class BacktestTrade extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "backtest_run_id", nullable = false)
    public Long backtestRunId;

    @Column(name = "outcome_signal_id", nullable = false, length = 64)
    public String outcomeSignalId;

    @Column(name = "outcome_fired_at", nullable = false)
    public Instant outcomeFiredAt;

    @Column(name = "symbol", nullable = false, length = 20)
    public String symbol;

    @Column(name = "direction", nullable = false, length = 8)
    public String direction;

    @Column(name = "original_signal", nullable = false, length = 16)
    public String originalSignal;

    @Column(name = "original_alignment", nullable = false)
    public int originalAlignment;

    @Column(name = "backtest_signal", nullable = false, length = 16)
    public String backtestSignal;

    @Column(name = "backtest_alignment", nullable = false)
    public int backtestAlignment;

    @Column(name = "realized_r_multiple")
    public Double realizedRMultiple;

    @Column(name = "backtest_would_issue", nullable = false)
    public boolean backtestWouldIssue;

    @Column(name = "contributed_r", nullable = false)
    public double contributedR = 0.0;
}
