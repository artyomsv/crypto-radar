package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.service.CandleClient;
import com.cryptoradar.signal.service.SignalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hourly shadow scan: for every symbol in the current overview, each enabled
 * {@link CandidateGenerator} produces one ATR-geometry candidate, which is scored
 * (stats always; LLM only when the generator opts in) and persisted as a PENDING
 * shadow candidate tagged by {@code generator.tag()}. Places no orders — only
 * collects data and predictions for the calibration report and Phase 2 model.
 */
@ApplicationScoped
public class ProbabilityScanScheduler {

    private static final Logger LOG = Logger.getLogger(ProbabilityScanScheduler.class);
    private static final String CANDLE_INTERVAL = "1h";
    private static final int CANDLE_LIMIT = 60;
    private static final int ATR_PERIOD = 14;

    @Inject SignalService signalService;
    @Inject CandleClient candleClient;
    @Inject WinProbabilityEstimator estimator;
    @Inject ProbabilityCalibrator calibrator;
    @Inject ProbabilityCandidateRepository repository;
    @Inject FeatureAssembler featureAssembler;
    @Inject ObjectMapper mapper;
    @Inject jakarta.enterprise.inject.Instance<CandidateGenerator> generators;

    @Scheduled(every = "{probability.scan.interval:1h}", delayed = "90s", identity = "probability-scan")
    void scan() {
        List<TradingSignal> signals = signalService.getSignalOverview().getSignals();
        int persisted = 0;
        for (TradingSignal signal : signals) {
            try {
                persisted += scanSymbol(signal);
            } catch (RuntimeException e) {
                LOG.warnf("Probability scan failed for %s: %s", signal.getSymbol(), e.getMessage());
            }
        }
        LOG.infof("Probability scan complete — %d candidates persisted across %d symbols",
                persisted, signals.size());
    }

    int scanSymbol(TradingSignal signal) {
        String symbol = signal.getSymbol();
        List<CandleBar> bars = candleClient.fetchRecent(symbol, CANDLE_INTERVAL, CANDLE_LIMIT);
        if (bars.size() < ATR_PERIOD + 1) {
            LOG.debugf("Skipping %s — insufficient candles (%d)", symbol, bars.size());
            return 0;
        }
        double atr = AtrCalculator.atr(bars, ATR_PERIOD);
        double entry = bars.get(bars.size() - 1).close();
        if (atr <= 0 || entry <= 0) return 0;

        Map<String, Double> dimScores = dimensionScores(signal);
        TechnicalIndicators indicators = TechnicalIndicators.compute(bars);
        DirectionContext ctx = new DirectionContext(signal, bars, atr, entry, indicators, dimScores);

        int persisted = 0;
        for (CandidateGenerator generator : generators) {
            if (!generator.enabled()) continue;
            try {
                Optional<Candidate> candidate = generator.build(ctx);
                if (candidate.isEmpty()) continue;
                persistScored(generator, ctx, candidate.get());
                persisted++;
            } catch (RuntimeException e) {
                LOG.warnf("Generator %s failed for %s: %s", generator.tag(), symbol, e.getMessage());
            }
        }
        return persisted;
    }

    // NOT @Transactional — all HTTP/scoring/feature-assembly happens here, outside any tx.
    // Holding a DB connection open across a 20s LLM round-trip causes connection-pool stalls
    // (same bug class as the ShadowOutcomeEvaluator fix in commit 8c45bf0).
    void persistScored(CandidateGenerator generator, DirectionContext ctx, Candidate candidate) {
        TradingSignal signal = ctx.signal();
        String symbol = signal.getSymbol();
        double statsProb = estimator.statsProbability(ctx.dimScores());
        Optional<GeminiProbabilityClient.LlmEstimate> llm = generator.runLlm()
                ? estimator.llmProbability(buildPrompt(symbol, candidate, signal, ctx.dimScores()))
                : Optional.empty();
        Double llmProb = llm.map(GeminiProbabilityClient.LlmEstimate::probability).orElse(null);
        Double calibratedProb = calibrator.calibrate(generator.tag(), llmProb);
        String featuresJson = toJson(featureAssembler.assemble(signal, candidate, ctx.bars(), ctx.dimScores()));

        ProbabilityCandidate row = new ProbabilityCandidate();
        row.scannedAt = Instant.now();
        row.symbol = symbol;
        row.direction = candidate.direction();
        row.entryPrice = candidate.entry();
        row.stopPrice = candidate.stop();
        row.targetPrice = candidate.target();
        row.atr = candidate.atr();
        row.riskReward = candidate.riskReward();
        row.statsProb = statsProb;
        row.llmProb = llmProb;
        row.llmReasoning = llm.map(GeminiProbabilityClient.LlmEstimate::reasoning).orElse(null);
        row.calibratedProb = calibratedProb;
        row.configTag = generator.tag();
        row.featuresJson = featuresJson;
        row.status = ProbabilityCandidate.STATUS_PENDING;
        persist(row);
    }

    @Transactional
    void persist(ProbabilityCandidate row) {
        repository.persist(row);
    }

    private String toJson(Map<String, Object> features) {
        try {
            return mapper.writeValueAsString(features);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Double> dimensionScores(TradingSignal signal) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (DimensionScore dim : signal.getDimensions()) {
            scores.put(dim.name(), dim.score());
        }
        return scores;
    }

    private String buildPrompt(String symbol, Candidate candidate, TradingSignal signal,
                               Map<String, Double> dimScores) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are estimating the probability that a crypto trade hits its target before its stop.\n");
        sb.append("Symbol: ").append(symbol).append("\n");
        sb.append("Direction: ").append(candidate.direction()).append("\n");
        sb.append(String.format("Entry: %.6f  Stop: %.6f  Target: %.6f  R:R: %.2f%n",
                candidate.entry(), candidate.stop(), candidate.target(), candidate.riskReward()));
        sb.append("Dimension scores (-100 bearish .. +100 bullish): ").append(dimScores).append("\n");
        sb.append("Overall score: ").append(signal.getOverallScore()).append("\n");
        sb.append("Respond with ONLY a JSON object: ");
        sb.append("{\"probability\": <0..1 chance target hit before stop>, \"reasoning\": \"<one sentence>\"}");
        return sb.toString();
    }
}
