package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionV5(
        @JsonProperty("orderId") String orderId,
        @JsonProperty("orderLinkId") String orderLinkId,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,
        @JsonProperty("orderType") String orderType,
        @JsonProperty("execType") String execType,
        @JsonProperty("execPrice") String execPrice,
        @JsonProperty("execQty") String execQty,
        @JsonProperty("execFee") String execFee,
        @JsonProperty("execTime") String execTime,
        @JsonProperty("stopOrderType") String stopOrderType
) {}
