package com.cryptoradar.execution.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "execution_events")
public class ExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_account_id", nullable = false)
    private Long exchangeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private ExecutionEventType eventType;

    @Column(name = "signal_id", length = 64)
    private String signalId;

    @Column(name = "executed_trade_id")
    private Long executedTradeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getExchangeAccountId() { return exchangeAccountId; }
    public void setExchangeAccountId(Long v) { this.exchangeAccountId = v; }
    public ExecutionEventType getEventType() { return eventType; }
    public void setEventType(ExecutionEventType v) { this.eventType = v; }
    public String getSignalId() { return signalId; }
    public void setSignalId(String v) { this.signalId = v; }
    public Long getExecutedTradeId() { return executedTradeId; }
    public void setExecutedTradeId(Long v) { this.executedTradeId = v; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> v) { this.metadata = v; }
    public Instant getCreatedAt() { return createdAt; }
}
