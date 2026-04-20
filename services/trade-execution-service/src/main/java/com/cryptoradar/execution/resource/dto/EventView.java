package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExecutionEvent;

import java.time.Instant;
import java.util.Map;

public record EventView(
        Long id, String eventType, String signalId, Long executedTradeId,
        Map<String, Object> metadata, Instant createdAt
) {
    public static EventView of(ExecutionEvent ev) {
        return new EventView(
                ev.getId(), ev.getEventType().name(), ev.getSignalId(),
                ev.getExecutedTradeId(), ev.getMetadata(), ev.getCreatedAt());
    }
}
