package com.cryptoradar.options.resource.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Decision-ready opportunity payload. Mirrors {@link OpportunityView} fields
 * then adds the per-leg snapshots, pre-summed pair Greeks, AND a
 * live-recomputed view of premium / IV / RV gap / signal overlay / confidence.
 *
 * <p>Why dual view: the scorer freezes its inputs into the
 * {@code option_opportunities} row at detection time. After detection, IV
 * reprices, vol regime shifts, signals fire — the original "buy cheap vol"
 * thesis may break before the user notices. The {@code live*} fields recompute
 * the same inputs from the freshest snapshot, so the UI can show drift and
 * hide opportunities whose live confidence has collapsed.
 *
 * <p>{@code isStale} is true when live confidence has dropped below the
 * scorer's firing threshold; the UI filters these out by default.
 */
public record EnrichedOpportunityView(
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
        Instant outcomeResolvedAt,
        OptionLegView callLeg,
        OptionLegView putLeg,
        Double netDelta,
        Double netGamma,
        Double netTheta,
        Double netVega,
        Double totalOpenInterest,
        Double totalVolume24h,
        // --- Live re-scored fields (computed at request time) ---
        Double livePremium,
        Double liveImpliedVolAtm,
        Double liveIvRvSpread,
        Double liveSignalOverlay,
        Double liveConfidence,
        Double liveUnderlyingPx,
        boolean isStale,
        String staleReason
) {}
