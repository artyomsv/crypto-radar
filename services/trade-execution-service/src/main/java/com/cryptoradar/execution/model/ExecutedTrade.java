package com.cryptoradar.execution.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "executed_trades")
public class ExecutedTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_account_id", nullable = false)
    private Long exchangeAccountId;

    @Column(name = "signal_id", length = 64)
    private String signalId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(length = 64)
    private String strategy;

    @Column(name = "exchange_order_id", length = 64)
    private String exchangeOrderId;

    @Column(name = "exchange_order_link_id", length = 64)
    private String exchangeOrderLinkId;

    @Column(name = "exchange_position_idx")
    private Integer exchangePositionIdx;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TradeStatus status;

    @Column(name = "entry_price", precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal qty;

    @Column
    private Integer leverage;

    @Column(name = "stop_price", precision = 20, scale = 8)
    private BigDecimal stopPrice;

    @Column(name = "target_price", precision = 20, scale = 8)
    private BigDecimal targetPrice;

    @Column(name = "dynamic_stop_price", precision = 20, scale = 8)
    private BigDecimal dynamicStopPrice;

    @Column(name = "trail_highest_r", precision = 10, scale = 4)
    private BigDecimal trailHighestR = BigDecimal.ZERO;

    @Column(name = "trail_triggered_at")
    private Instant trailTriggeredAt;

    @Column(name = "exit_price", precision = 20, scale = 8)
    private BigDecimal exitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason", length = 24)
    private ExitReason exitReason;

    @Column(name = "realized_pnl_usdt", precision = 20, scale = 8)
    private BigDecimal realizedPnlUsdt;

    @Column(name = "realized_r_multiple", precision = 10, scale = 4)
    private BigDecimal realizedRMultiple;

    @Column(name = "fees_usdt", precision = 20, scale = 8)
    private BigDecimal feesUsdt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (openedAt == null) openedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getExchangeAccountId() { return exchangeAccountId; }
    public void setExchangeAccountId(Long v) { this.exchangeAccountId = v; }
    public String getSignalId() { return signalId; }
    public void setSignalId(String v) { this.signalId = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String v) { this.strategy = v; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String v) { this.exchangeOrderId = v; }
    public String getExchangeOrderLinkId() { return exchangeOrderLinkId; }
    public void setExchangeOrderLinkId(String v) { this.exchangeOrderLinkId = v; }
    public Integer getExchangePositionIdx() { return exchangePositionIdx; }
    public void setExchangePositionIdx(Integer v) { this.exchangePositionIdx = v; }
    public TradeStatus getStatus() { return status; }
    public void setStatus(TradeStatus v) { this.status = v; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal v) { this.entryPrice = v; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal v) { this.qty = v; }
    public Integer getLeverage() { return leverage; }
    public void setLeverage(Integer v) { this.leverage = v; }
    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal v) { this.stopPrice = v; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal v) { this.targetPrice = v; }
    public BigDecimal getDynamicStopPrice() { return dynamicStopPrice; }
    public void setDynamicStopPrice(BigDecimal v) { this.dynamicStopPrice = v; }
    public BigDecimal getTrailHighestR() { return trailHighestR; }
    public void setTrailHighestR(BigDecimal v) { this.trailHighestR = v; }
    public Instant getTrailTriggeredAt() { return trailTriggeredAt; }
    public void setTrailTriggeredAt(Instant v) { this.trailTriggeredAt = v; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal v) { this.exitPrice = v; }
    public ExitReason getExitReason() { return exitReason; }
    public void setExitReason(ExitReason v) { this.exitReason = v; }
    public BigDecimal getRealizedPnlUsdt() { return realizedPnlUsdt; }
    public void setRealizedPnlUsdt(BigDecimal v) { this.realizedPnlUsdt = v; }
    public BigDecimal getRealizedRMultiple() { return realizedRMultiple; }
    public void setRealizedRMultiple(BigDecimal v) { this.realizedRMultiple = v; }
    public BigDecimal getFeesUsdt() { return feesUsdt; }
    public void setFeesUsdt(BigDecimal v) { this.feesUsdt = v; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant v) { this.openedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant v) { this.lastSyncAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
