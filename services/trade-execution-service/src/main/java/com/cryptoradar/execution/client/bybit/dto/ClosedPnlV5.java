package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClosedPnlV5(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("orderId") String orderId,
        @JsonProperty("side") String side,
        @JsonProperty("qty") String qty,
        @JsonProperty("orderPrice") String orderPrice,
        @JsonProperty("avgEntryPrice") String avgEntryPrice,
        @JsonProperty("avgExitPrice") String avgExitPrice,
        @JsonProperty("closedPnl") String closedPnl,
        @JsonProperty("openFee") String openFee,
        @JsonProperty("closeFee") String closeFee,
        @JsonProperty("createdTime") String createdTime,
        @JsonProperty("updatedTime") String updatedTime
) {}
