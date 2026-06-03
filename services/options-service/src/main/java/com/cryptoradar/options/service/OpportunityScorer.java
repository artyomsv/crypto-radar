package com.cryptoradar.options.service;

import com.cryptoradar.options.event.OpportunityPublisher;
import com.cryptoradar.options.model.OptionOpportunity;
import com.cryptoradar.options.model.OptionSnapshot;
import com.cryptoradar.options.repository.OptionOpportunityRepository;
import com.cryptoradar.options.repository.OptionSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * For each underlying, pulls the latest chain, finds the ATM straddle (or
 * nearest OTM strangle), compares the implied vol to internally-computed
 * realized vol + Bybit's HV + signal-overlay activity, scores 0-100, and
 * persists a row to {@code option_opportunities} when confidence ≥ threshold.
 *
 * <p>Confidence formula:
 * <pre>
 *   iv_rv_gap_score = clamp((rv14d - iv_atm) / rv14d * 100, 0, 100)
 *   confidence      = 0.6 * iv_rv_gap_score + 0.4 * signal_overlay
 * </pre>
 * Positive {@code (rv - iv)} = realized vol exceeds implied = options are cheap
 * relative to historical movement = strangle expected payoff positive.
 */
@ApplicationScoped
public class OpportunityScorer {

    private static final Logger LOG = Logger.getLogger(OpportunityScorer.class);

    @Inject OptionSnapshotRepository snapshotRepo;
    @Inject OptionOpportunityRepository opportunityRepo;
    @Inject RealizedVolService rvService;
    @Inject SignalOverlayService overlayService;
    @Inject OpportunityPublisher publisher;

    @ConfigProperty(name = "options.opportunity.confidence-threshold", defaultValue = "70.0")
    double confidenceThreshold;

    /**
     * How long to suppress duplicate opportunity rows for the same leg pair.
     * The scorer fires every 60s; without a cooldown each setup gets
     * re-detected and re-inserted N times until expiry. Default 60 min:
     * stale-enough that genuine re-entries (price moved, picked new strikes)
     * still record, but close-enough that a stable IV/RV gap stays as one row.
     */
    @ConfigProperty(name = "options.opportunity.dedup-cooldown-minutes", defaultValue = "60")
    int dedupCooldownMinutes;

    public void scoreAllUnderlyings(List<String> underlyings) {
        for (String u : underlyings) {
            try {
                scoreOne(u);
            } catch (RuntimeException e) {
                LOG.warnf(e, "scoring failed for %s — continuing", u);
            }
        }
    }

    void scoreOne(String underlying) {
        List<OptionSnapshot> chain = snapshotRepo.latestChain(underlying);
        if (chain.isEmpty()) return;

        // Group by expiry, pick the earliest one we have a full chain for.
        Map<LocalDate, List<OptionSnapshot>> byExpiry = new LinkedHashMap<>();
        for (OptionSnapshot s : chain) {
            byExpiry.computeIfAbsent(s.getExpiry(), k -> new java.util.ArrayList<>()).add(s);
        }
        Optional<LocalDate> earliestExpiry = byExpiry.keySet().stream()
                .filter(d -> !d.isBefore(LocalDate.now()))
                .min(LocalDate::compareTo);
        if (earliestExpiry.isEmpty()) return;
        LocalDate expiry = earliestExpiry.get();
        List<OptionSnapshot> contracts = byExpiry.get(expiry);

        Strangle strangle = pickStrangle(contracts);
        if (strangle == null) return;

        Double rv14 = rvService.computeAnnualized(underlying, 14);
        Double rv7 = rvService.computeAnnualized(underlying, 7);
        Double ivAtm = strangle.atmIv;

        double ivRvGapScore = computeGapScore(ivAtm, rv14);
        double overlay = overlayService.score(underlying);
        double confidence = 0.6 * ivRvGapScore + 0.4 * overlay;

        if (confidence < confidenceThreshold) return;

        // Dedup: if we already wrote an open opportunity for these exact legs
        // within the cooldown window, skip the insert. Otherwise the scorer's
        // 60s tick fills the watchlist with near-identical cards.
        if (opportunityRepo.existsOpenForLegs(
                strangle.callSymbol, strangle.putSymbol, dedupCooldownMinutes)) {
            LOG.debugf("opportunity dedup-skip %s %s/%s",
                    underlying, strangle.callSymbol, strangle.putSymbol);
            return;
        }

        OptionOpportunity opp = new OptionOpportunity();
        opp.setUnderlying(underlying);
        opp.setExpiry(expiry);
        opp.setStrikeCall(strangle.callStrike);
        opp.setStrikePut(strangle.putStrike);
        opp.setCallSymbol(strangle.callSymbol);
        opp.setPutSymbol(strangle.putSymbol);
        opp.setStranglePremium(strangle.premium);
        opp.setImpliedVolAtm(ivAtm);
        opp.setRealizedVol7d(rv7);
        opp.setRealizedVol14d(rv14);
        opp.setIvRvSpread(rv14 != null && ivAtm != null ? rv14 - ivAtm : null);
        opp.setSignalOverlay(overlay);
        opp.setConfidence(confidence);

        Map<String, Object> meta = new HashMap<>();
        meta.put("underlyingPx", strangle.underlyingPx);
        meta.put("ivRvGapScore", ivRvGapScore);
        opp.setMetadata(meta);

        opportunityRepo.persist(opp);
        publisher.publish(opp);
        LOG.infof("opportunity detected %s expiry=%s confidence=%.1f premium=%.2f",
                underlying, expiry, confidence, strangle.premium);
    }

    /**
     * Picks the ATM straddle (call_strike == put_strike == nearest to spot)
     * when available. Falls back to closest-OTM strangle (lowest call strike
     * above spot + highest put strike below spot).
     */
    Strangle pickStrangle(List<OptionSnapshot> contracts) {
        double spot = contracts.stream()
                .map(OptionSnapshot::getUnderlyingPx)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(0.0);
        if (spot <= 0) return null;

        OptionSnapshot callBest = null;
        OptionSnapshot putBest = null;
        double bestCallDist = Double.MAX_VALUE;
        double bestPutDist = Double.MAX_VALUE;
        for (OptionSnapshot s : contracts) {
            if (s.getAsk() == null || s.getAsk() <= 0) continue;
            double dist = Math.abs(s.getStrike() - spot);
            if ("C".equals(s.getOptionType()) && dist < bestCallDist) {
                bestCallDist = dist;
                callBest = s;
            }
            if ("P".equals(s.getOptionType()) && dist < bestPutDist) {
                bestPutDist = dist;
                putBest = s;
            }
        }
        if (callBest == null || putBest == null) return null;

        Strangle st = new Strangle();
        st.callStrike = callBest.getStrike();
        st.putStrike = putBest.getStrike();
        st.callSymbol = callBest.getSymbol();
        st.putSymbol = putBest.getSymbol();
        st.premium = callBest.getAsk() + putBest.getAsk();
        st.underlyingPx = spot;
        // ATM IV = avg of call+put IV when both available.
        Double cIv = callBest.getImpliedVol();
        Double pIv = putBest.getImpliedVol();
        if (cIv != null && pIv != null) st.atmIv = (cIv + pIv) / 2.0;
        else if (cIv != null) st.atmIv = cIv;
        else st.atmIv = pIv;
        return st;
    }

    static double computeGapScore(Double iv, Double rv) {
        if (iv == null || rv == null || rv <= 0) return 0.0;
        double gapPct = (rv - iv) / rv * 100.0;
        return Math.max(0.0, Math.min(100.0, gapPct));
    }

    static class Strangle {
        double callStrike;
        double putStrike;
        String callSymbol;
        String putSymbol;
        double premium;
        double underlyingPx;
        Double atmIv;
    }
}
