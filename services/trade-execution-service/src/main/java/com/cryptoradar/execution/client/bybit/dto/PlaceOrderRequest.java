package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceOrderRequest(
        @JsonProperty("category") String category,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,
        @JsonProperty("orderType") String orderType,
        @JsonProperty("qty") String qty,
        @JsonProperty("takeProfit") String takeProfit,
        @JsonProperty("stopLoss") String stopLoss,
        @JsonProperty("tpslMode") String tpslMode,
        @JsonProperty("tpOrderType") String tpOrderType,
        @JsonProperty("slOrderType") String slOrderType,
        @JsonProperty("orderLinkId") String orderLinkId,
        @JsonProperty("reduceOnly") Boolean reduceOnly
) {}
