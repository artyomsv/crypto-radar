package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExecutedTrade;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeView(
        Long id, String signalId, String symbol, String direction, String strategy,
        String status, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal qty,
        BigDecimal realizedPnlUsdt, BigDecimal realizedRMultiple, BigDecimal feesUsdt,
        String exitReason, Instant openedAt, Instant closedAt
) {
    public static TradeView of(ExecutedTrade t) {
        return new TradeView(
                t.getId(), t.getSignalId(), t.getSymbol(), t.getDirection(), t.getStrategy(),
                t.getStatus().name(), t.getEntryPrice(), t.getExitPrice(), t.getQty(),
                t.getRealizedPnlUsdt(), t.getRealizedRMultiple(), t.getFeesUsdt(),
                t.getExitReason() == null ? null : t.getExitReason().name(),
                t.getOpenedAt(), t.getClosedAt());
    }
}
