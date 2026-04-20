package com.cryptoradar.execution.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "exchange_accounts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exchange", "environment"}))
public class ExchangeAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String exchange;

    @Column(nullable = false, length = 16)
    private String environment;

    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "api_secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiSecretEncrypted;

    @Column(length = 64)
    private String label;

    @Column(name = "auto_trade_enabled", nullable = false)
    private boolean autoTradeEnabled = false;

    @Column(name = "kill_switch", nullable = false)
    private boolean killSwitch = true;

    @Column(name = "risk_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal riskPercent = new BigDecimal("1.00");

    @Column(name = "default_leverage", nullable = false)
    private int defaultLeverage = 3;

    @Column(name = "max_concurrent_positions", nullable = false)
    private int maxConcurrentPositions = 5;

    @Column(name = "max_daily_loss_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDailyLossPercent = new BigDecimal("5.00");

    @Column(name = "signal_age_seconds", nullable = false)
    private int signalAgeSeconds = 60;

    @Column(name = "position_max_age_hours", nullable = false)
    private int positionMaxAgeHours = 24;

    @Column(name = "flip_persistence_ticks", nullable = false)
    private int flipPersistenceTicks = 2;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getApiSecretEncrypted() { return apiSecretEncrypted; }
    public void setApiSecretEncrypted(String apiSecretEncrypted) { this.apiSecretEncrypted = apiSecretEncrypted; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isAutoTradeEnabled() { return autoTradeEnabled; }
    public void setAutoTradeEnabled(boolean autoTradeEnabled) { this.autoTradeEnabled = autoTradeEnabled; }

    public boolean isKillSwitch() { return killSwitch; }
    public void setKillSwitch(boolean killSwitch) { this.killSwitch = killSwitch; }

    public BigDecimal getRiskPercent() { return riskPercent; }
    public void setRiskPercent(BigDecimal riskPercent) { this.riskPercent = riskPercent; }

    public int getDefaultLeverage() { return defaultLeverage; }
    public void setDefaultLeverage(int defaultLeverage) { this.defaultLeverage = defaultLeverage; }

    public int getMaxConcurrentPositions() { return maxConcurrentPositions; }
    public void setMaxConcurrentPositions(int maxConcurrentPositions) { this.maxConcurrentPositions = maxConcurrentPositions; }

    public BigDecimal getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public void setMaxDailyLossPercent(BigDecimal maxDailyLossPercent) { this.maxDailyLossPercent = maxDailyLossPercent; }

    public int getSignalAgeSeconds() { return signalAgeSeconds; }
    public void setSignalAgeSeconds(int signalAgeSeconds) { this.signalAgeSeconds = signalAgeSeconds; }

    public int getPositionMaxAgeHours() { return positionMaxAgeHours; }
    public void setPositionMaxAgeHours(int positionMaxAgeHours) { this.positionMaxAgeHours = positionMaxAgeHours; }

    public int getFlipPersistenceTicks() { return flipPersistenceTicks; }
    public void setFlipPersistenceTicks(int flipPersistenceTicks) { this.flipPersistenceTicks = flipPersistenceTicks; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
