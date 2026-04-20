package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceOrderResult(
        @JsonProperty("orderId") String orderId,
        @JsonProperty("orderLinkId") String orderLinkId
) {}
