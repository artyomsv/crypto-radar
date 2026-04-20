package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletV5(
        @JsonProperty("totalEquity") String totalEquity,
        @JsonProperty("totalAvailableBalance") String totalAvailableBalance,
        @JsonProperty("totalPerpUPL") String totalPerpUPL,
        @JsonProperty("totalWalletBalance") String totalWalletBalance
) {}
