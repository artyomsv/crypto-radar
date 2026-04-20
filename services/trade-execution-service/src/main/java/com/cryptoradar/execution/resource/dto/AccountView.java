package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExchangeAccount;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountView(
        Long id,
        String exchange,
        String environment,
        String label,
        String keyMask,
        boolean autoTradeEnabled,
        boolean killSwitch,
        BigDecimal riskPercent,
        int defaultLeverage,
        int maxConcurrentPositions,
        BigDecimal maxDailyLossPercent,
        int signalAgeSeconds,
        int positionMaxAgeHours,
        int flipPersistenceTicks,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Build a view from an entity. {@code plaintextApiKey} is passed from the
     * decrypt-to-render step (in-memory); this method extracts just the last 4
     * chars as a key mask to show in the UI. If null, keyMask is "****".
     */
    public static AccountView of(ExchangeAccount a, String plaintextApiKey) {
        String mask = "****";
        if (plaintextApiKey != null && plaintextApiKey.length() >= 4) {
            mask = "****" + plaintextApiKey.substring(plaintextApiKey.length() - 4);
        }
        return new AccountView(
                a.getId(),
                a.getExchange(),
                a.getEnvironment(),
                a.getLabel(),
                mask,
                a.isAutoTradeEnabled(),
                a.isKillSwitch(),
                a.getRiskPercent(),
                a.getDefaultLeverage(),
                a.getMaxConcurrentPositions(),
                a.getMaxDailyLossPercent(),
                a.getSignalAgeSeconds(),
                a.getPositionMaxAgeHours(),
                a.getFlipPersistenceTicks(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
