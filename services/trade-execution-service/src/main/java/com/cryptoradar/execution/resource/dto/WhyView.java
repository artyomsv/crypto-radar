package com.cryptoradar.execution.resource.dto;

import java.time.Instant;
import java.util.Map;

public record WhyView(
        Long tradeId,
        String signalId,
        String symbol,
        String direction,
        String strategy,
        Instant openedAt,
        Map<String, Object> signalSnapshot
) {}
