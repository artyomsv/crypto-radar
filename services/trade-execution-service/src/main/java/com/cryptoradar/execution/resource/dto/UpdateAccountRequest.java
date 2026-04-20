package com.cryptoradar.execution.resource.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UpdateAccountRequest(
        String label,
        Boolean autoTradeEnabled,
        Boolean killSwitch,
        @Positive BigDecimal riskPercent,
        @Min(1) Integer defaultLeverage,
        @Min(1) Integer maxConcurrentPositions,
        @Positive BigDecimal maxDailyLossPercent,
        @Min(1) Integer signalAgeSeconds,
        @Min(1) Integer positionMaxAgeHours,
        @Min(1) Integer flipPersistenceTicks
) {}
