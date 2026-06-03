package com.cryptoradar.options.resource.dto;

import com.cryptoradar.options.model.OptionSnapshot;

import java.time.Instant;

/**
 * Single-leg snapshot for the enriched opportunity response. A subset of
 * {@code ChainRowView} fields focused on what the strangle/straddle card
 * needs at decision time: pricing, IV, Greeks, OI, volume.
 */
public record OptionLegView(
        Instant time,
        String symbol,
        String optionType,
        double strike,
        Double bid,
        Double ask,
        Double mark,
        Double impliedVol,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        Double openInterest,
        Double volume24h
) {
    public static OptionLegView from(OptionSnapshot s) {
        return new OptionLegView(
                s.getTime(), s.getSymbol(), s.getOptionType(), s.getStrike(),
                s.getBid(), s.getAsk(), s.getMark(), s.getImpliedVol(),
                s.getDelta(), s.getGamma(), s.getTheta(), s.getVega(),
                s.getOpenInterest(), s.getVolume24h());
    }
}
