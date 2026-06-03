package com.cryptoradar.signal.backtest;

import com.cryptoradar.core.TrailCalculator;
import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.signal.config.ConfigVersionRepository;
import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.config.SignalConfigVersion;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 1 backtest engine.
 *
 * <p>Replays stored {@code signal_outcomes} against a proposed {@link SignalConfig},
 * computing what the engine would have emitted and comparing it to the original.
 *
 * <p>Auto-trigger: {@code ConfigService.saveVersion} should call
 * {@code runTier1(newVersionId, now-30d, now)} after a successful save.
 * TODO: Auto-trigger from ConfigService.saveVersion happens once Agent A's class exists.
 */
@ApplicationScoped
public class BacktestService {

    private static final Logger LOG = LoggerFactory.getLogger(BacktestService.class);

    private static final int MAX_PERIOD_DAYS = 365;

    private final SignalOutcomeRepository outcomeRepository;
    private final BacktestRepository backtestRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final ObjectMapper objectMapper;

    public BacktestService(SignalOutcomeRepository outcomeRepository,
                           BacktestRepository backtestRepository,
                           ConfigVersionRepository configVersionRepository,
                           ObjectMapper objectMapper) {
        this.outcomeRepository = outcomeRepository;
        this.backtestRepository = backtestRepository;
        this.configVersionRepository = configVersionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs a Tier 1 backtest against stored outcomes in the given window.
     *
     * @param configVersionId the {@code signal_config_versions} row to test
     * @param start           inclusive window start
     * @param end             inclusive window end
     * @return persisted {@link BacktestRun} with aggregate metrics
     * @throws NotFoundException    if {@code configVersionId} does not exist
     * @throws BadRequestException  if the period is invalid
     */
    @Transactional
    public BacktestRun runTier1(long configVersionId, Instant start, Instant end) {
        return runTier1(configVersionId, start, end, null);
    }

    /**
     * Tier 1 with an optional execution-side alignment floor. When non-null,
     * outcomes whose original alignment falls below this floor are treated as
     * "not issued" (excluded from win/loss aggregation) — simulates the
     * trade-execution-service Vector D gate without re-running the engine.
     *
     * <p>Trail simulation: outcomes that originally closed via TRAIL_STOP are
     * re-walked under the candidate config's trail ladder using the recorded
     * MFE peak. Cheap approximation — assumes the trail-trigger price still
     * hits before the next bar's worth of price action, which is true for any
     * outcome whose MAE didn't first take out the new trail level. Bar-by-bar
     * fidelity would need 1m candle re-walk (Tier 2 / future work).
     */
    @Transactional
    public BacktestRun runTier1(long configVersionId, Instant start, Instant end,
                                 Integer alignmentFloor) {
        validatePeriod(start, end);

        SignalConfigVersion configVersion = loadConfigVersion(configVersionId);
        SignalConfig config = configVersion.getConfig();

        long startMs = System.currentTimeMillis();
        MDC.put("configVersionId", String.valueOf(configVersionId));
        LOG.info("backtest_start period=[{} -> {}]", start, end);

        List<SignalOutcome> outcomes = loadOutcomes(start, end);

        if (outcomes.isEmpty()) {
            LOG.warn("backtest_no_outcomes configVersionId={} period=[{} -> {}]",
                    configVersionId, start, end);
        }

        List<BacktestTrade> trades = new ArrayList<>(outcomes.size());
        Aggregates agg = new Aggregates();

        for (SignalOutcome outcome : outcomes) {
            BacktestTrade trade = scoreOutcome(outcome, config, configVersionId);
            applyAlignmentFloor(trade, outcome, alignmentFloor);
            applyTrailSimulation(trade, outcome, config.trail());
            trades.add(trade);
            accumulate(agg, trade, outcome);
        }

        BacktestRun run = buildRun(configVersionId, start, end, agg, trades,
                startMs, config);
        backtestRepository.save(run);
        linkAndSaveTrades(trades, run.id);

        long durationMs = System.currentTimeMillis() - startMs;
        MDC.put("durationMs", String.valueOf(durationMs));
        MDC.put("tradeCount", String.valueOf(run.tradeCount));
        LOG.info("backtest_complete configVersionId={} tradeCount={} totalR={} durationMs={}",
                configVersionId, run.tradeCount, run.totalR, durationMs);
        MDC.remove("configVersionId");
        MDC.remove("durationMs");
        MDC.remove("tradeCount");

        return run;
    }

    // --- private helpers ---

    private void validatePeriod(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new BadRequestException("periodEnd must be after periodStart");
        }
        if (end.isAfter(Instant.now())) {
            throw new BadRequestException("periodEnd must not be in the future");
        }
        long days = ChronoUnit.DAYS.between(start, end);
        if (days > MAX_PERIOD_DAYS) {
            throw new BadRequestException("Period exceeds maximum of " + MAX_PERIOD_DAYS + " days");
        }
    }

    private SignalConfigVersion loadConfigVersion(long configVersionId) {
        return configVersionRepository.findById(configVersionId)
                .orElseThrow(() -> new NotFoundException(
                        "Config version not found: " + configVersionId));
    }

    private List<SignalOutcome> loadOutcomes(Instant start, Instant end) {
        return outcomeRepository.find(
                "firedAt >= ?1 and firedAt <= ?2 and status != ?3",
                start, end, OutcomeStatus.PENDING
        ).list();
    }

    private BacktestTrade scoreOutcome(SignalOutcome outcome, SignalConfig config, long runId) {
        List<DimensionScore> dims = buildDimensions(outcome, config.weights());
        double overallScore = BacktestScorer.computeOverallScore(
                outcome.getTechnicalScore(), outcome.getWhaleScore(),
                outcome.getDerivativesScore(), outcome.getSentimentScore(),
                outcome.getOrderbookScore(), outcome.getMacroScore(),
                config.weights());
        int backtestAlignment = BacktestScorer.computeAlignment(dims, overallScore, config.alignment());
        String backtestSignal = BacktestScorer.determineSignalLabel(overallScore, backtestAlignment, config.signalLabels());

        boolean wouldIssue = !BacktestScorer.NEUTRAL.equals(backtestSignal);
        Double realizedR = outcome.getRealizedRMultiple();
        double contributedR = (wouldIssue && realizedR != null) ? realizedR : 0.0;

        BacktestTrade trade = new BacktestTrade();
        trade.outcomeSignalId = outcome.getSignalId();
        trade.outcomeFiredAt = outcome.getFiredAt();
        trade.symbol = outcome.getSymbol();
        trade.direction = outcome.getDirection();
        trade.originalSignal = outcome.getSignalType();
        trade.originalAlignment = outcome.getAlignment();
        trade.backtestSignal = backtestSignal;
        trade.backtestAlignment = backtestAlignment;
        trade.realizedRMultiple = realizedR;
        trade.backtestWouldIssue = wouldIssue;
        trade.contributedR = contributedR;
        return trade;
    }

    private List<DimensionScore> buildDimensions(SignalOutcome outcome, SignalConfig.Weights w) {
        List<DimensionScore> dims = new ArrayList<>(6);
        dims.add(new DimensionScore("Technical", nullToZero(outcome.getTechnicalScore()), w.technical(), List.of()));
        dims.add(new DimensionScore("Whale", nullToZero(outcome.getWhaleScore()), w.whale(), List.of()));
        dims.add(new DimensionScore("Derivatives", nullToZero(outcome.getDerivativesScore()), w.derivatives(), List.of()));
        dims.add(new DimensionScore("Sentiment", nullToZero(outcome.getSentimentScore()), w.sentiment(), List.of()));
        dims.add(new DimensionScore("Order Book", nullToZero(outcome.getOrderbookScore()), w.orderBook(), List.of()));
        dims.add(new DimensionScore("Macro", nullToZero(outcome.getMacroScore()), w.macro(), List.of()));
        return dims;
    }

    private void accumulate(Aggregates agg, BacktestTrade trade, SignalOutcome outcome) {
        // original metrics: any outcome that was non-NEUTRAL (any non-PENDING closed outcome)
        String originalType = outcome.getSignalType();
        if (!BacktestScorer.NEUTRAL.equals(originalType)) {
            agg.originalTradeCount++;
            if (outcome.getRealizedRMultiple() != null) {
                agg.originalTotalR += outcome.getRealizedRMultiple();
            }
        }

        if (!trade.backtestWouldIssue) return;

        agg.tradeCount++;
        agg.totalR += trade.contributedR;
        if (trade.contributedR > 0) {
            agg.winCount++;
            agg.totalWinnerR += trade.contributedR;
        } else if (trade.contributedR < 0) {
            agg.lossCount++;
            agg.totalLoserR += trade.contributedR;
        }
    }

    private BacktestRun buildRun(long configVersionId, Instant start, Instant end,
                                  Aggregates agg, List<BacktestTrade> trades,
                                  long startMs, SignalConfig config) {
        BacktestRun run = new BacktestRun();
        run.configVersionId = configVersionId;
        run.periodStart = start;
        run.periodEnd = end;
        run.tier = 1;
        run.tradeCount = agg.tradeCount;
        run.winCount = agg.winCount;
        run.lossCount = agg.lossCount;
        run.totalR = roundR(agg.totalR);
        run.avgR = agg.tradeCount > 0 ? roundR(agg.totalR / agg.tradeCount) : 0.0;
        run.avgWinnerR = agg.winCount > 0 ? roundR(agg.totalWinnerR / agg.winCount) : null;
        run.avgLoserR = agg.lossCount > 0 ? roundR(agg.totalLoserR / agg.lossCount) : null;
        run.originalTradeCount = agg.originalTradeCount;
        run.originalTotalR = roundR(agg.originalTotalR);
        run.metadata = buildMetadata(trades, config);
        run.durationMs = (int) (System.currentTimeMillis() - startMs);
        run.createdAt = Instant.now();
        return run;
    }

    private void linkAndSaveTrades(List<BacktestTrade> trades, long runId) {
        for (BacktestTrade trade : trades) {
            trade.backtestRunId = runId;
        }
        backtestRepository.saveTrades(trades);
    }

    private String buildMetadata(List<BacktestTrade> trades, SignalConfig config) {
        Map<String, Object> symbolStats = new HashMap<>();
        for (BacktestTrade trade : trades) {
            if (!trade.backtestWouldIssue) continue;
            symbolStats.merge(trade.symbol, trade.contributedR, (a, b) -> (double) a + (double) b);
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("perSymbolR", symbolStats);
        meta.put("configWeights", Map.of(
                "technical", config.weights().technical(),
                "whale", config.weights().whale(),
                "derivatives", config.weights().derivatives(),
                "sentiment", config.weights().sentiment(),
                "orderBook", config.weights().orderBook(),
                "macro", config.weights().macro()
        ));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize backtest metadata", e);
            return "{}";
        }
    }

    private static double nullToZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /**
     * Mirrors trade-execution-service's Vector D gate. When the floor is set
     * and the original alignment is below it, the trade is marked not-issued
     * so it contributes 0R to aggregates.
     */
    private static void applyAlignmentFloor(BacktestTrade trade, SignalOutcome outcome, Integer floor) {
        if (floor == null) return;
        Integer original = outcome.getAlignment();
        if (original == null) return;
        if (original < floor) {
            trade.backtestWouldIssue = false;
            trade.contributedR = 0.0;
        }
    }

    /**
     * Re-walks the trail ladder under the candidate config for outcomes that
     * originally exited via TRAIL_STOP. Replaces {@code contributedR} with the
     * new trail-out R derived from the recorded MFE peak. Other exit reasons
     * (TARGET / INITIAL_STOP / STAGNATION / EXPIRED) are unaffected — the
     * trail config does not influence those outcomes.
     */
    private static void applyTrailSimulation(BacktestTrade trade, SignalOutcome outcome,
                                              SignalConfig.Trail trail) {
        if (!trade.backtestWouldIssue) return;
        if (!"TRAIL_STOP".equals(outcome.getFinalExitReason())) return;
        Double simulated = simulateTrailExitR(outcome, trail);
        if (simulated == null) return;
        trade.contributedR = simulated;
        trade.realizedRMultiple = simulated;
    }

    static Double simulateTrailExitR(SignalOutcome outcome, SignalConfig.Trail t) {
        Double entry = outcome.getEntryPrice();
        Double stop = outcome.getStopPrice();
        Double mfePct = outcome.getMaxFavorablePct();
        if (entry == null || stop == null || mfePct == null || entry <= 0) return null;
        double riskPct = Math.abs(entry - stop) / entry * 100.0;
        if (riskPct <= 0) return null;
        double mfeR = mfePct / riskPct;
        TrailConfig tc = new TrailConfig(
                t.activationR(), t.stepR(), t.offsetR(),
                t.widerOffsetActivationR(), t.widerOffsetR());
        // Trail never engaged: original close was TRAIL_STOP but new
        // activation never reached → outcome would have ridden the initial
        // stop instead. Approximate as -1R (the canonical 1R loss).
        return TrailCalculator.computeNewTrailR(mfeR, tc, 0.0).orElse(-1.0);
    }

    private static double roundR(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** Mutable accumulator — avoids allocating a record per outcome. */
    private static final class Aggregates {
        int tradeCount;
        int winCount;
        int lossCount;
        double totalR;
        double totalWinnerR;
        double totalLoserR;
        int originalTradeCount;
        double originalTotalR;
    }
}
