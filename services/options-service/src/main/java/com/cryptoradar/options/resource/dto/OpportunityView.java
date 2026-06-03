package com.cryptoradar.options.resource.dto;

import com.cryptoradar.options.model.OptionOpportunity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Flat DTO for the {@code /api/options/opportunities} endpoints. Mirrors
 * the entity 1:1 with primitive types only — no JPA leakage to frontend.
 */
public record OpportunityView(
        Long id,
        Instant detectedAt,
        String underlying,
        LocalDate expiry,
        double strikeCall,
        double strikePut,
        String callSymbol,
        String putSymbol,
        double stranglePremium,
        Double impliedVolAtm,
        Double realizedVol7d,
        Double realizedVol14d,
        Double ivRvSpread,
        Double signalOverlay,
        double confidence,
        Map<String, Object> metadata,
        Double realizedMovePct,
        Double outcomePnlPct,
        Instant outcomeResolvedAt
) {
    public static OpportunityView from(OptionOpportunity o) {
        return new OpportunityView(
                o.getId(), o.getDetectedAt(), o.getUnderlying(), o.getExpiry(),
                o.getStrikeCall(), o.getStrikePut(), o.getCallSymbol(), o.getPutSymbol(),
                o.getStranglePremium(), o.getImpliedVolAtm(),
                o.getRealizedVol7d(), o.getRealizedVol14d(), o.getIvRvSpread(),
                o.getSignalOverlay(), o.getConfidence(), o.getMetadata(),
                o.getRealizedMovePct(), o.getOutcomePnlPct(), o.getOutcomeResolvedAt());
    }
}
