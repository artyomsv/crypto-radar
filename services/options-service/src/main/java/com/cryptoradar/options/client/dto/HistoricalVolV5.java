package com.cryptoradar.options.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Historical volatility reading from
 * {@code GET /v5/market/historical-volatility?category=option&baseCoin=BTC&period=30}.
 *
 * <p>Response wraps a list of these — one per discrete timestamp Bybit publishes.
 * We only persist the latest reading each poll cycle.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoricalVolV5(
        @JsonProperty("period") int period,
        @JsonProperty("value") String value,
        @JsonProperty("time") String timeMs
) {}
