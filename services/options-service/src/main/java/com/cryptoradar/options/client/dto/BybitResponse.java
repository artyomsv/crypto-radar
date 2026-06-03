package com.cryptoradar.options.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard Bybit V5 envelope: {@code retCode=0} means success.
 * Mirrors the shape used by trade-execution-service but kept local here so
 * options-service has no cross-service dependencies.
 */
public record BybitResponse<T>(
        @JsonProperty("retCode") int retCode,
        @JsonProperty("retMsg") String retMsg,
        @JsonProperty("result") T result,
        @JsonProperty("time") long time
) {
    public boolean isOk() {
        return retCode == 0;
    }
}
