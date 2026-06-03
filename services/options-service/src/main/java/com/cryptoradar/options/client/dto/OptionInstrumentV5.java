package com.cryptoradar.options.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Options instrument metadata from {@code /v5/market/instruments-info?category=option}.
 * The symbol embeds expiry + strike + type (e.g. {@code BTC-30MAY25-72000-C}).
 * Bybit also returns these as separate fields:
 *
 * <pre>
 *   "symbol":"BTC-30MAY25-72000-C",
 *   "optionsType":"Call",
 *   "deliveryTime":"1748563200000",
 *   "deliveryFeeRate":"0.00015",
 *   "baseCoin":"BTC",
 *   "quoteCoin":"USDT" or "USD",
 *   "settleCoin":"USDC",
 *   "status":"Trading",
 *   "launchTime":"...",
 *   "strikePrice":"72000.00"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionInstrumentV5(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("optionsType") String optionsType,
        @JsonProperty("baseCoin") String baseCoin,
        @JsonProperty("quoteCoin") String quoteCoin,
        @JsonProperty("settleCoin") String settleCoin,
        @JsonProperty("status") String status,
        @JsonProperty("deliveryTime") String deliveryTimeMs,
        @JsonProperty("launchTime") String launchTimeMs,
        @JsonProperty("strikePrice") String strikePrice
) {}
