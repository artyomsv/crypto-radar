package com.cryptoradar.execution.resource.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Pattern(regexp = "BYBIT") String exchange,
        @NotBlank @Pattern(regexp = "DEMO|MAINNET") String environment,
        @NotBlank String apiKey,
        @NotBlank String apiSecret,
        String label,
        @JsonProperty("riskPercent") @Positive BigDecimal riskPercent,
        @JsonProperty("defaultLeverage") @Min(1) Integer defaultLeverage,
        @JsonProperty("maxConcurrentPositions") @Min(1) Integer maxConcurrentPositions,
        @JsonProperty("maxDailyLossPercent") @Positive BigDecimal maxDailyLossPercent,
        @JsonProperty("signalAgeSeconds") @Min(1) Integer signalAgeSeconds,
        @JsonProperty("positionMaxAgeHours") @Min(1) Integer positionMaxAgeHours,
        @JsonProperty("flipPersistenceTicks") @Min(1) Integer flipPersistenceTicks
) {}
