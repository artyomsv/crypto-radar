package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionV5(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,
        @JsonProperty("positionIdx") int positionIdx,
        @JsonProperty("size") String size,
        @JsonProperty("avgPrice") String avgPrice,
        @JsonProperty("leverage") String leverage,
        @JsonProperty("stopLoss") String stopLoss,
        @JsonProperty("takeProfit") String takeProfit,
        @JsonProperty("unrealisedPnl") String unrealisedPnl,
        @JsonProperty("createdTime") String createdTime,
        @JsonProperty("updatedTime") String updatedTime
) {}
