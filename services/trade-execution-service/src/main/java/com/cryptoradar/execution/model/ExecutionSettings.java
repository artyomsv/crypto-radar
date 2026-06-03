package com.cryptoradar.execution.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton row holding the execution-side gate parameters. Enforced by the
 * {@code one_execution_settings_row} CHECK constraint in the DB.
 *
 * <p>Replaces six former {@code @ConfigProperty} reads scattered across
 * {@code SignalSubscriber}, {@code SymbolPerformanceGate},
 * {@code DetectorConfluenceCheck} and {@code DailyPnlCalculator}. Hot-reloaded
 * by {@code ExecutionSettingsService}.
 */
@Entity
@Table(name = "execution_settings")
public class ExecutionSettings {

    @Id
    private Long id = 1L;

    @Column(name = "alignment_floor", nullable = false)
    private Integer alignmentFloor = 70;

    @Column(name = "symbol_gate_enabled", nullable = false)
    private Boolean symbolGateEnabled = true;

    @Column(name = "symbol_gate_lookback", nullable = false)
    private Integer symbolGateLookback = 10;

    @Column(name = "symbol_gate_threshold_r", nullable = false)
    private Double symbolGateThresholdR = -3.0;

    @Column(name = "symbol_gate_cache_ttl_sec", nullable = false)
    private Integer symbolGateCacheTtlSec = 30;

    @Column(name = "confluence_trend_required", nullable = false)
    private Boolean confluenceTrendRequired = true;

    @Column(name = "confluence_window_minutes", nullable = false)
    private Integer confluenceWindowMinutes = 15;

    @Column(name = "daily_pnl_equity_cache_ttl_sec", nullable = false)
    private Integer dailyPnlEquityCacheTtlSec = 60;

    @Column(name = "telegram_enabled", nullable = false)
    private Boolean telegramEnabled = false;

    // AES-GCM ciphertext of the Telegram bot token (same scheme as exchange
    // API creds). Plaintext never lives in a column, a field, or a log line.
    @Column(name = "telegram_bot_token_enc")
    private String telegramBotTokenEnc;

    @Column(name = "telegram_chat_id")
    private String telegramChatId;

    // CSV of ExecutionEventType names the user opted into. NULL = use defaults.
    @Column(name = "telegram_notified_events")
    private String telegramNotifiedEvents;

    // Opt-in for option-opportunity alerts (sourced from options-service via
    // the crypto:options:opportunities Redis channel, not an ExecutionEventType).
    @Column(name = "telegram_notify_options", nullable = false)
    private Boolean telegramNotifyOptions = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getAlignmentFloor() { return alignmentFloor; }
    public void setAlignmentFloor(Integer alignmentFloor) { this.alignmentFloor = alignmentFloor; }

    public Boolean getSymbolGateEnabled() { return symbolGateEnabled; }
    public void setSymbolGateEnabled(Boolean symbolGateEnabled) { this.symbolGateEnabled = symbolGateEnabled; }

    public Integer getSymbolGateLookback() { return symbolGateLookback; }
    public void setSymbolGateLookback(Integer symbolGateLookback) { this.symbolGateLookback = symbolGateLookback; }

    public Double getSymbolGateThresholdR() { return symbolGateThresholdR; }
    public void setSymbolGateThresholdR(Double symbolGateThresholdR) { this.symbolGateThresholdR = symbolGateThresholdR; }

    public Integer getSymbolGateCacheTtlSec() { return symbolGateCacheTtlSec; }
    public void setSymbolGateCacheTtlSec(Integer symbolGateCacheTtlSec) { this.symbolGateCacheTtlSec = symbolGateCacheTtlSec; }

    public Boolean getConfluenceTrendRequired() { return confluenceTrendRequired; }
    public void setConfluenceTrendRequired(Boolean confluenceTrendRequired) { this.confluenceTrendRequired = confluenceTrendRequired; }

    public Integer getConfluenceWindowMinutes() { return confluenceWindowMinutes; }
    public void setConfluenceWindowMinutes(Integer confluenceWindowMinutes) { this.confluenceWindowMinutes = confluenceWindowMinutes; }

    public Integer getDailyPnlEquityCacheTtlSec() { return dailyPnlEquityCacheTtlSec; }
    public void setDailyPnlEquityCacheTtlSec(Integer dailyPnlEquityCacheTtlSec) { this.dailyPnlEquityCacheTtlSec = dailyPnlEquityCacheTtlSec; }

    public Boolean getTelegramEnabled() { return telegramEnabled; }
    public void setTelegramEnabled(Boolean telegramEnabled) { this.telegramEnabled = telegramEnabled; }

    public String getTelegramBotTokenEnc() { return telegramBotTokenEnc; }
    public void setTelegramBotTokenEnc(String telegramBotTokenEnc) { this.telegramBotTokenEnc = telegramBotTokenEnc; }

    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }

    public String getTelegramNotifiedEvents() { return telegramNotifiedEvents; }
    public void setTelegramNotifiedEvents(String telegramNotifiedEvents) { this.telegramNotifiedEvents = telegramNotifiedEvents; }

    public Boolean getTelegramNotifyOptions() { return telegramNotifyOptions; }
    public void setTelegramNotifyOptions(Boolean telegramNotifyOptions) { this.telegramNotifyOptions = telegramNotifyOptions; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
