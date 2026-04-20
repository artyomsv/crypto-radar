package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExecutedTrade;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionView(
        Long id, Long accountId, String signalId, String symbol, String direction,
        String strategy, String status, BigDecimal entryPrice, BigDecimal qty,
        Integer leverage, BigDecimal stopPrice, BigDecimal targetPrice,
        BigDecimal dynamicStopPrice, BigDecimal trailHighestR,
        Instant trailTriggeredAt, Instant openedAt
) {
    public static PositionView of(ExecutedTrade t) {
        return new PositionView(
                t.getId(), t.getExchangeAccountId(), t.getSignalId(), t.getSymbol(),
                t.getDirection(), t.getStrategy(), t.getStatus().name(),
                t.getEntryPrice(), t.getQty(), t.getLeverage(),
                t.getStopPrice(), t.getTargetPrice(), t.getDynamicStopPrice(),
                t.getTrailHighestR(), t.getTrailTriggeredAt(), t.getOpenedAt());
    }
}
