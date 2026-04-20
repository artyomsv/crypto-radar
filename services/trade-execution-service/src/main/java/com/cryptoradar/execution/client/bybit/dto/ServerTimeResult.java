package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerTimeResult(
        @JsonProperty("timeSecond") String timeSecond,
        @JsonProperty("timeNano") String timeNano
) {}
