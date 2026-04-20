package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SetLeverageRequest(
        @JsonProperty("category") String category,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("buyLeverage") String buyLeverage,
        @JsonProperty("sellLeverage") String sellLeverage
) {}
