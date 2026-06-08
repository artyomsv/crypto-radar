package com.cryptoradar.options.service;

import com.cryptoradar.options.event.OpportunityPublisher;
import com.cryptoradar.options.model.OptionShortVolOpportunity;
import com.cryptoradar.options.model.OptionSnapshot;
import com.cryptoradar.options.repository.OptionShortVolOpportunityRepository;
import com.cryptoradar.options.repository.OptionSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Companion to {@link OpportunityScorer} — fires when conditions favor a
 * defined-risk short-vol structure (iron condor or credit spread). Mirrors
 * the long-vol scorer's structure so future evaluation tooling can treat
 * both strategies uniformly.
 *
 * <p>Confidence formula (Sinclair-aligned):
 * <pre>
 *   ivRvPremiumScore   = clamp(0, 100, (iv_atm - rv14) / rv14 * 50)
 *   termStructureScore = quiet term structure (placeholder for Tier 1)
 *   signalQuietScore   = 100 - overlay_score
 *   ivPercentileScore  = current_iv_percentile_30d
 *   confidence         = 0.5*premium + 0.2*term + 0.2*quiet + 0.1*percentile
 * </pre>
 *
 * <p>Tier 1: alert-only. No execution. See
 * {@code docs/knowledge-base/10-projectr-x-mapping/05-vol-strategy-plan.md}.
 */
@ApplicationScoped
public class ShortVolOpportunityScorer {

    private static final Logger LOG = Logger.getLogger(ShortVolOpportunityScorer.class);

    @Inject OptionSnapshotRepository snapshotRepo;
    @Inject OptionShortVolOpportunityRepository repo;
    @Inject RealizedVolService rvService;
    @Inject SignalOverlayService overlayService;
    @Inject OpportunityPublisher publisher;

    @ConfigProperty(name = "options.short-vol.confidence-threshold", defaultValue = "80.0")
    double confidenceThreshold;

    @ConfigProperty(name = "options.short-vol.dedup-cooldown-minutes", defaultValue = "240")
    int dedupCooldownMinutes;

    /**
     * IV must exceed RV14 by at least this absolute % gap before we even
     * consider scoring (Sinclair Ch. 8 — vol risk premium needs material
     * head-room to clear fees+slippage on iron condor structures).
     */
    @ConfigProperty(name = "options.short-vol.min-iv-rv-premium-pct", defaultValue = "25.0")
    double minIvRvPremiumPct;

    /**
     * Target absolute delta for the short leg of a credit spread / iron
     * condor. ≈ 1 stdev OTM. Sinclair Ch. 5 — 0.20–0.30 delta is the band
     * where premium is meaningful AND probability-of-profit is high enough
     * to compound favourably.
     */
    @ConfigProperty(name = "options.short-vol.short-leg-target-delta", defaultValue = "0.25")
    double shortLegTargetDelta;

    /**
     * How many strikes further OTM the long-leg hedge sits. Wider = more
     * credit but more max-loss. 1-strike is the tightest defined-risk
     * structure. We start there.
     */
    @ConfigProperty(name = "options.short-vol.hedge-strike-offset", defaultValue = "1")
    int hedgeStrikeOffset;

    public void scoreAllUnderlyings(List<String> underlyings) {
        for (String u : underlyings) {
            try {
                scoreOne(u);
            } catch (RuntimeException e) {
                LOG.warnf(e, "short-vol scoring failed for %s — continuing", u);
            }
        }
    }

    public double currentThreshold() {
        return confidenceThreshold;
    }

    public Diagnostic diagnose(String underlying) {
        Diagnostic d = new Diagnostic();
        d.underlying = underlying;

        List<OptionSnapshot> chain = snapshotRepo.latestChain(underlying);
        if (chain.isEmpty()) {
            d.exitReason = "no_chain";
            return d;
        }
        Map<LocalDate, List<OptionSnapshot>> byExpiry = new LinkedHashMap<>();
        for (OptionSnapshot s : chain) {
            byExpiry.computeIfAbsent(s.getExpiry(), k -> new ArrayList<>()).add(s);
        }
        Optional<LocalDate> earliestExpiry = byExpiry.keySet().stream()
                .filter(date -> !date.isBefore(LocalDate.now()))
                .min(LocalDate::compareTo);
        if (earliestExpiry.isEmpty()) {
            d.exitReason = "no_future_expiry";
            return d;
        }
        d.expiry = earliestExpiry.get();
        List<OptionSnapshot> contracts = byExpiry.get(d.expiry);

        d.spot = contracts.stream()
                .map(OptionSnapshot::getUnderlyingPx)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);

        Double rv14 = rvService.computeAnnualized(underlying, 14);
        Double atmIv = avgAtmIvPct(contracts, d.spot);
        d.rv14Pct = rv14;
        d.atmIvPct = atmIv;

        if (atmIv == null || rv14 == null || rv14 <= 0) {
            d.exitReason = "missing_iv_or_rv";
            return d;
        }
        d.ivRvPremiumPct = (atmIv - rv14) / rv14 * 100.0;
        if (d.ivRvPremiumPct < minIvRvPremiumPct) {
            d.exitReason = "below_min_iv_rv_premium";
            return d;
        }

        // Score components.
        d.ivRvPremiumScore = clamp01_100((atmIv - rv14) / rv14 * 50.0);
        d.termStructureScore = 70.0;  // Tier 1 placeholder; Tier 2 wires real term-structure data
        d.signalQuietScore = 100.0 - overlayService.score(underlying);
        Double percentile = rvService.computeIvPercentileLast30d(underlying);
        d.ivPercentileScore = percentile == null ? 50.0 : percentile;

        d.confidence = 0.5 * d.ivRvPremiumScore
                + 0.2 * d.termStructureScore
                + 0.2 * d.signalQuietScore
                + 0.1 * d.ivPercentileScore;
        d.threshold = confidenceThreshold;
        d.wouldFire = d.confidence >= confidenceThreshold;

        if (!d.wouldFire) {
            d.exitReason = "below_threshold";
            return d;
        }

        // Pick the defined-risk structure (iron condor by default).
        Structure st = pickStructure(contracts, d.spot);
        if (st == null) {
            d.exitReason = "no_structure_available";
            return d;
        }
        d.structureType = st.type;
        d.shortCallSymbol = st.shortCallSymbol;
        d.shortPutSymbol = st.shortPutSymbol;
        d.longCallSymbol = st.longCallSymbol;
        d.longPutSymbol = st.longPutSymbol;
        d.netCredit = st.netCredit;
        d.maxLossUsd = st.maxLossUsd;
        d.popPct = st.popPct;
        d.breakEvenLow = st.breakEvenLow;
        d.breakEvenHigh = st.breakEvenHigh;

        d.dedupSkipped = repo.existsOpenForShortLegs(
                st.shortCallSymbol, st.shortPutSymbol, dedupCooldownMinutes);
        d.exitReason = Boolean.TRUE.equals(d.dedupSkipped) ? "dedup_skip" : "would_persist";
        return d;
    }

    void scoreOne(String underlying) {
        Diagnostic d = diagnose(underlying);
        if (!"would_persist".equals(d.exitReason)) return;

        OptionShortVolOpportunity opp = new OptionShortVolOpportunity();
        opp.setDetectedAt(java.time.Instant.now());
        opp.setUnderlying(underlying);
        opp.setExpiry(d.expiry);
        opp.setStructureType(d.structureType);
        opp.setShortCallSymbol(d.shortCallSymbol);
        opp.setShortPutSymbol(d.shortPutSymbol);
        opp.setLongCallSymbol(d.longCallSymbol);
        opp.setLongPutSymbol(d.longPutSymbol);
        opp.setNetCredit(d.netCredit);
        opp.setMaxLossUsd(d.maxLossUsd);
        opp.setPopPct(d.popPct);
        opp.setBreakEvenLow(d.breakEvenLow);
        opp.setBreakEvenHigh(d.breakEvenHigh);
        opp.setImpliedVolAtm(d.atmIvPct);
        opp.setRealizedVol14d(d.rv14Pct);
        opp.setIvRvPremiumPct(d.ivRvPremiumPct);
        opp.setTermStructureScore(d.termStructureScore);
        opp.setSignalQuietScore(d.signalQuietScore);
        opp.setIvPercentileScore(d.ivPercentileScore);
        opp.setConfidence(d.confidence);

        Map<String, Object> meta = new HashMap<>();
        if (d.spot != null) meta.put("spot", d.spot);
        meta.put("ivRvPremiumScore", d.ivRvPremiumScore);
        opp.setMetadata(meta);

        repo.persist(opp);
        publisher.publishShortVol(opp);
        LOG.infof("short-vol opportunity detected %s expiry=%s structure=%s confidence=%.1f credit=%.2f maxLoss=%.2f",
                underlying, d.expiry, d.structureType, d.confidence, d.netCredit, d.maxLossUsd);
    }

    private static Double avgAtmIvPct(List<OptionSnapshot> contracts, Double spot) {
        if (spot == null || spot <= 0) return null;
        OptionSnapshot bestCall = null;
        OptionSnapshot bestPut = null;
        double bestCallDist = Double.MAX_VALUE;
        double bestPutDist = Double.MAX_VALUE;
        for (OptionSnapshot s : contracts) {
            if (s.getImpliedVol() == null) continue;
            double dist = Math.abs(s.getStrike() - spot);
            if ("C".equals(s.getOptionType()) && dist < bestCallDist) {
                bestCallDist = dist;
                bestCall = s;
            }
            if ("P".equals(s.getOptionType()) && dist < bestPutDist) {
                bestPutDist = dist;
                bestPut = s;
            }
        }
        if (bestCall == null && bestPut == null) return null;
        double sum = 0; int n = 0;
        if (bestCall != null) { sum += bestCall.getImpliedVol(); n++; }
        if (bestPut != null) { sum += bestPut.getImpliedVol(); n++; }
        return (sum / n) * 100.0;  // stored decimal → percent
    }

    /**
     * Picks an iron condor — sells a call near {@code shortLegTargetDelta}
     * delta, sells a put at the same target delta, buys hedges one strike
     * further OTM on each side. Falls back to single-side credit spreads if
     * one side doesn't have a hedge strike.
     */
    Structure pickStructure(List<OptionSnapshot> contracts, Double spot) {
        if (spot == null || spot <= 0) return null;

        List<OptionSnapshot> calls = contracts.stream()
                .filter(s -> "C".equals(s.getOptionType())
                        && s.getDelta() != null
                        && s.getBid() != null && s.getBid() > 0
                        && s.getAsk() != null && s.getAsk() > 0)
                .sorted(Comparator.comparingDouble(OptionSnapshot::getStrike))
                .toList();
        List<OptionSnapshot> puts = contracts.stream()
                .filter(s -> "P".equals(s.getOptionType())
                        && s.getDelta() != null
                        && s.getBid() != null && s.getBid() > 0
                        && s.getAsk() != null && s.getAsk() > 0)
                .sorted(Comparator.comparingDouble(OptionSnapshot::getStrike))
                .toList();

        OptionSnapshot shortCall = pickShortByDelta(calls, shortLegTargetDelta);
        OptionSnapshot shortPut = pickShortByDelta(puts, shortLegTargetDelta);
        if (shortCall == null && shortPut == null) return null;

        OptionSnapshot longCall = shortCall == null ? null : nthStrikeAbove(calls, shortCall, hedgeStrikeOffset);
        OptionSnapshot longPut = shortPut == null ? null : nthStrikeBelow(puts, shortPut, hedgeStrikeOffset);

        Structure s = new Structure();
        if (shortCall != null && longCall != null && shortPut != null && longPut != null) {
            s.type = "IRON_CONDOR";
            populateIronCondor(s, shortCall, longCall, shortPut, longPut);
        } else if (shortCall != null && longCall != null) {
            s.type = "CREDIT_SPREAD_CALL";
            populateCreditSpreadCall(s, shortCall, longCall);
        } else if (shortPut != null && longPut != null) {
            s.type = "CREDIT_SPREAD_PUT";
            populateCreditSpreadPut(s, shortPut, longPut);
        } else {
            return null;  // can't build a defined-risk structure with what we have
        }
        return s;
    }

    private static OptionSnapshot pickShortByDelta(List<OptionSnapshot> sortedContracts, double targetAbsDelta) {
        OptionSnapshot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (OptionSnapshot s : sortedContracts) {
            double dist = Math.abs(Math.abs(s.getDelta()) - targetAbsDelta);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = s;
            }
        }
        return best;
    }

    private static OptionSnapshot nthStrikeAbove(List<OptionSnapshot> sortedAsc,
                                                  OptionSnapshot anchor, int offset) {
        int idx = sortedAsc.indexOf(anchor);
        if (idx < 0 || idx + offset >= sortedAsc.size()) return null;
        return sortedAsc.get(idx + offset);
    }

    private static OptionSnapshot nthStrikeBelow(List<OptionSnapshot> sortedAsc,
                                                  OptionSnapshot anchor, int offset) {
        int idx = sortedAsc.indexOf(anchor);
        if (idx < offset) return null;
        return sortedAsc.get(idx - offset);
    }

    private static void populateIronCondor(Structure s,
                                            OptionSnapshot shortCall, OptionSnapshot longCall,
                                            OptionSnapshot shortPut, OptionSnapshot longPut) {
        s.shortCallSymbol = shortCall.getSymbol();
        s.shortCallStrike = shortCall.getStrike();
        s.shortCallDelta = shortCall.getDelta();
        s.longCallSymbol = longCall.getSymbol();
        s.longCallStrike = longCall.getStrike();
        s.shortPutSymbol = shortPut.getSymbol();
        s.shortPutStrike = shortPut.getStrike();
        s.shortPutDelta = shortPut.getDelta();
        s.longPutSymbol = longPut.getSymbol();
        s.longPutStrike = longPut.getStrike();
        double callCredit = shortCall.getBid() - longCall.getAsk();
        double putCredit = shortPut.getBid() - longPut.getAsk();
        s.netCredit = callCredit + putCredit;
        double callWidth = longCall.getStrike() - shortCall.getStrike();
        double putWidth = shortPut.getStrike() - longPut.getStrike();
        // Max loss = max(call width, put width) - net credit (assuming same width per side).
        s.maxLossUsd = Math.max(callWidth, putWidth) - s.netCredit;
        // PoP approximation (Sinclair Ch. 5): 1 - (|short_call_delta| + |short_put_delta|)
        s.popPct = (1.0 - (Math.abs(shortCall.getDelta()) + Math.abs(shortPut.getDelta()))) * 100.0;
        s.breakEvenLow = shortPut.getStrike() - s.netCredit;
        s.breakEvenHigh = shortCall.getStrike() + s.netCredit;
    }

    private static void populateCreditSpreadCall(Structure s, OptionSnapshot shortCall, OptionSnapshot longCall) {
        s.shortCallSymbol = shortCall.getSymbol();
        s.shortCallStrike = shortCall.getStrike();
        s.shortCallDelta = shortCall.getDelta();
        s.longCallSymbol = longCall.getSymbol();
        s.longCallStrike = longCall.getStrike();
        s.netCredit = shortCall.getBid() - longCall.getAsk();
        s.maxLossUsd = (longCall.getStrike() - shortCall.getStrike()) - s.netCredit;
        s.popPct = (1.0 - Math.abs(shortCall.getDelta())) * 100.0;
        s.breakEvenLow = null;
        s.breakEvenHigh = shortCall.getStrike() + s.netCredit;
    }

    private static void populateCreditSpreadPut(Structure s, OptionSnapshot shortPut, OptionSnapshot longPut) {
        s.shortPutSymbol = shortPut.getSymbol();
        s.shortPutStrike = shortPut.getStrike();
        s.shortPutDelta = shortPut.getDelta();
        s.longPutSymbol = longPut.getSymbol();
        s.longPutStrike = longPut.getStrike();
        s.netCredit = shortPut.getBid() - longPut.getAsk();
        s.maxLossUsd = (shortPut.getStrike() - longPut.getStrike()) - s.netCredit;
        s.popPct = (1.0 - Math.abs(shortPut.getDelta())) * 100.0;
        s.breakEvenLow = shortPut.getStrike() - s.netCredit;
        s.breakEvenHigh = null;
    }

    private static double clamp01_100(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }

    static class Structure {
        String type;
        String shortCallSymbol;
        Double shortCallStrike;
        Double shortCallDelta;
        String longCallSymbol;
        Double longCallStrike;
        String shortPutSymbol;
        Double shortPutStrike;
        Double shortPutDelta;
        String longPutSymbol;
        Double longPutStrike;
        double netCredit;
        double maxLossUsd;
        Double popPct;
        Double breakEvenLow;
        Double breakEvenHigh;
    }

    /** Diagnostic decomposition; same pattern as {@link OpportunityScorer.Diagnostic}. */
    public static class Diagnostic {
        public String underlying;
        public LocalDate expiry;
        public Double spot;
        public Double atmIvPct;
        public Double rv14Pct;
        public Double ivRvPremiumPct;
        public Double ivRvPremiumScore;
        public Double termStructureScore;
        public Double signalQuietScore;
        public Double ivPercentileScore;
        public Double confidence;
        public Double threshold;
        public Boolean wouldFire;
        public String structureType;
        public String shortCallSymbol;
        public String shortPutSymbol;
        public String longCallSymbol;
        public String longPutSymbol;
        public Double netCredit;
        public Double maxLossUsd;
        public Double popPct;
        public Double breakEvenLow;
        public Double breakEvenHigh;
        public Boolean dedupSkipped;
        public String exitReason;
    }
}
