package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradingStopRequest(
        @JsonProperty("category") String category,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("stopLoss") String stopLoss,
        @JsonProperty("tpslMode") String tpslMode,
        @JsonProperty("positionIdx") Integer positionIdx
) {}
