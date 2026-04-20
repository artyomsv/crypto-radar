package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiKeyPermissionsV5(
        @JsonProperty("id") String id,
        @JsonProperty("note") String note,
        @JsonProperty("apiKey") String apiKey,
        @JsonProperty("readOnly") int readOnly,
        @JsonProperty("permissions") Map<String, List<String>> permissions
) {}
