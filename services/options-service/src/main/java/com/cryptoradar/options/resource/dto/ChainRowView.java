package com.cryptoradar.options.resource.dto;

import com.cryptoradar.options.model.OptionSnapshot;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row in the option chain endpoint. Flat for direct frontend consumption.
 */
public record ChainRowView(
        Instant time,
        String symbol,
        String underlying,
        LocalDate expiry,
        double strike,
        String optionType,
        Double bid,
        Double ask,
        Double mark,
        Double impliedVol,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        Double openInterest,
        Double volume24h,
        Double underlyingPx
) {
    public static ChainRowView from(OptionSnapshot s) {
        return new ChainRowView(
                s.getTime(), s.getSymbol(), s.getUnderlying(), s.getExpiry(),
                s.getStrike(), s.getOptionType(),
                s.getBid(), s.getAsk(), s.getMark(), s.getImpliedVol(),
                s.getDelta(), s.getGamma(), s.getTheta(), s.getVega(),
                s.getOpenInterest(), s.getVolume24h(), s.getUnderlyingPx());
    }
}
