package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic Bybit V5 response envelope: {retCode, retMsg, result, time, retExtInfo}.
 * The {@code result} payload is endpoint-specific; parameterize {@code T} per call site.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BybitResponse<T>(
        @JsonProperty("retCode") int retCode,
        @JsonProperty("retMsg") String retMsg,
        @JsonProperty("result") T result,
        @JsonProperty("time") long time
) {
    public boolean isOk() { return retCode == 0; }
}
