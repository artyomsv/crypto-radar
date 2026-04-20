package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.model.TradeSetup;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persists actionable signals to {@code signal_outcomes} so they can be
 * evaluated after the fact. The feedback loop starts here.
 *
 * <p>Dedups within a symbol+direction: if there's already an open PENDING
 * outcome for the same direction, the incoming signal is treated as a
 * continuation of the same trade idea and a new row is NOT created. This
 * prevents oscillation between BUY and STRONG_BUY from inflating the dataset.
 */
@ApplicationScoped
public class OutcomeTracker {

    private static final Logger LOG = Logger.getLogger(OutcomeTracker.class);

    private static final String DIRECTION_LONG = "LONG";
    private static final String DIRECTION_SHORT = "SHORT";
    private static final String STRATEGY_DIMENSION_SCORING = "dimension-scoring";

    private static final Set<String> LONG_SIGNALS = Set.of("BUY", "STRONG_BUY");
    private static final Set<String> SHORT_SIGNALS = Set.of("SELL", "STRONG_SELL");

    private static final String DIM_TECHNICAL = "Technical";
    private static final String DIM_WHALE = "Whale";
    private static final String DIM_DERIVATIVES = "Derivatives";
    private static final String DIM_SENTIMENT = "Sentiment";
    private static final String DIM_ORDER_BOOK = "OrderBook";
    private static final String DIM_MACRO = "Macro";

    private final SignalOutcomeRepository repository;

    public OutcomeTracker(SignalOutcomeRepository repository) {
        this.repository = repository;
    }

    /**
     * Called by {@code SignalService} whenever a signal transitions from
     * neutral (or the opposite direction) to an actionable state. No-op if
     * the signal is not actionable or is missing trade levels.
     */
    @Transactional
    public void trackTransition(TradingSignal signal) {
        if (!isTrackable(signal)) return;

        String direction = resolveDirection(signal.getSignal());
        if (direction == null) return;

        boolean alreadyOpen = repository
                .findOpenByStrategy(signal.getSymbol(), direction, STRATEGY_DIMENSION_SCORING)
                .isPresent();
        if (alreadyOpen) {
            LOG.debugf("Skipping %s %s dimension-scoring — open outcome already exists",
                    signal.getSymbol(), direction);
            return;
        }

        SignalOutcome outcome = buildOutcome(signal, direction);
        repository.persist(outcome);
        LOG.infof("Tracking %s %s %s entry=%.4f stop=%.4f target=%.4f rr=%.2f",
                outcome.getStrategy(), outcome.getSymbol(), outcome.getDirection(),
                outcome.getEntryPrice(), outcome.getStopPrice(),
                outcome.getTargetPrice(), outcome.getRiskRewardRatio());
    }

    /**
     * Called by {@code SignalService} when a {@code TradeSetupDetector}
     * emits a setup. Dedups per {@code (symbol, direction, strategy)} so
     * each detector maintains at most one open outcome for a given symbol
     * and direction at a time. Different strategies do NOT conflict.
     */
    @Transactional
    public void trackSetup(TradeSetup setup) {
        if (setup == null || setup.direction() == null) return;

        boolean alreadyOpen = repository
                .findOpenByStrategy(setup.symbol(), setup.direction(), setup.strategy())
                .isPresent();
        if (alreadyOpen) {
            LOG.debugf("Skipping %s %s %s — open outcome already exists",
                    setup.symbol(), setup.direction(), setup.strategy());
            return;
        }

        SignalOutcome outcome = buildOutcomeFromSetup(setup);
        repository.persist(outcome);
        LOG.infof("Tracking %s %s %s entry=%.4f stop=%.4f target=%.4f rr=%.2f conf=%d",
                outcome.getStrategy(), outcome.getSymbol(), outcome.getDirection(),
                outcome.getEntryPrice(), outcome.getStopPrice(),
                outcome.getTargetPrice(), outcome.getRiskRewardRatio(), outcome.getConfidence());
    }

    private SignalOutcome buildOutcomeFromSetup(TradeSetup setup) {
        SignalOutcome outcome = new SignalOutcome();
        outcome.setFiredAt(setup.firedAt() != null ? setup.firedAt() : Instant.now());
        outcome.setSignalId(UUID.randomUUID().toString());
        outcome.setSymbol(setup.symbol());
        outcome.setStrategy(setup.strategy());
        outcome.setSignalType(setup.signalType());
        outcome.setDirection(setup.direction());
        outcome.setEntryPrice(setup.entryPrice());
        outcome.setStopPrice(setup.stopPrice());
        outcome.setTargetPrice(setup.targetPrice());
        outcome.setRiskRewardRatio(setup.riskRewardRatio());
        outcome.setConfidence(setup.confidence());
        outcome.setOverallScore((double) setup.confidence());
        outcome.setAiAnalysis(formatReasons(setup));
        applyTrailConfig(outcome, setup.trailConfig());
        return outcome;
    }

    /**
     * Copies per-strategy trail parameters from the detector-supplied config
     * onto the outcome. Leaves the entity-level defaults in place when no
     * config was supplied (legacy setups or the dimension-scoring path).
     */
    private void applyTrailConfig(SignalOutcome outcome, com.cryptoradar.signal.model.TrailConfig config) {
        if (config == null) return;
        outcome.setTrailActivationR(config.activationR());
        outcome.setTrailStepR(config.stepR());
        outcome.setTrailOffsetR(config.offsetR());
    }

    private String formatReasons(TradeSetup setup) {
        if (setup.reasons() == null || setup.reasons().isEmpty()) return null;
        return String.join(" | ", setup.reasons());
    }

    private boolean isTrackable(TradingSignal signal) {
        return signal != null
                && signal.getSuggestedEntry() != null
                && signal.getSuggestedStopLoss() != null
                && signal.getSuggestedTakeProfit() != null
                && signal.getRiskRewardRatio() != null
                && resolveDirection(signal.getSignal()) != null;
    }

    private String resolveDirection(String signalType) {
        if (signalType == null) return null;
        if (LONG_SIGNALS.contains(signalType)) return DIRECTION_LONG;
        if (SHORT_SIGNALS.contains(signalType)) return DIRECTION_SHORT;
        return null;
    }

    private SignalOutcome buildOutcome(TradingSignal signal, String direction) {
        SignalOutcome outcome = new SignalOutcome();
        outcome.setFiredAt(signal.getTimestamp() != null ? signal.getTimestamp() : Instant.now());
        outcome.setSignalId(UUID.randomUUID().toString());
        outcome.setSymbol(signal.getSymbol());
        outcome.setSignalType(signal.getSignal());
        outcome.setDirection(direction);
        outcome.setEntryPrice(signal.getSuggestedEntry());
        outcome.setStopPrice(signal.getSuggestedStopLoss());
        outcome.setTargetPrice(signal.getSuggestedTakeProfit());
        outcome.setRiskRewardRatio(signal.getRiskRewardRatio());
        outcome.setConfidence(signal.getConfidence());
        outcome.setOverallScore(signal.getOverallScore());
        outcome.setAiAnalysis(signal.getAiAnalysis());
        applyDimensionScores(outcome, signal.getDimensions());
        return outcome;
    }

    private void applyDimensionScores(SignalOutcome outcome, List<DimensionScore> dimensions) {
        if (dimensions == null) return;
        for (DimensionScore dim : dimensions) {
            writeDimensionScore(outcome, dim);
        }
    }

    private void writeDimensionScore(SignalOutcome outcome, DimensionScore dim) {
        switch (dim.name()) {
            case DIM_TECHNICAL   -> outcome.setTechnicalScore(dim.score());
            case DIM_WHALE       -> outcome.setWhaleScore(dim.score());
            case DIM_DERIVATIVES -> outcome.setDerivativesScore(dim.score());
            case DIM_SENTIMENT   -> outcome.setSentimentScore(dim.score());
            case DIM_ORDER_BOOK  -> outcome.setOrderbookScore(dim.score());
            case DIM_MACRO       -> outcome.setMacroScore(dim.score());
            default -> { /* unknown dimension — ignore rather than fail */ }
        }
    }
}
