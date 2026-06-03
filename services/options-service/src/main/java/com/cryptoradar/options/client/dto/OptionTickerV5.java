package com.cryptoradar.options.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One contract row in the Bybit V5 options tickers response. Bybit returns
 * every numeric value as a string — leave as String here and let
 * {@code OptionsCollectorService} convert with null-tolerant helpers.
 *
 * <p>Reference: {@code GET /v5/market/tickers?category=option&baseCoin=BTC}
 * (response shape per Bybit V5 docs, Jan 2025).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionTickerV5(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("bid1Price") String bid,
        @JsonProperty("ask1Price") String ask,
        @JsonProperty("markPrice") String mark,
        @JsonProperty("indexPrice") String indexPrice,
        @JsonProperty("underlyingPrice") String underlyingPrice,
        @JsonProperty("markIv") String markIv,
        @JsonProperty("bid1Iv") String bidIv,
        @JsonProperty("ask1Iv") String askIv,
        @JsonProperty("delta") String delta,
        @JsonProperty("gamma") String gamma,
        @JsonProperty("theta") String theta,
        @JsonProperty("vega") String vega,
        @JsonProperty("openInterest") String openInterest,
        @JsonProperty("volume24h") String volume24h
) {}
