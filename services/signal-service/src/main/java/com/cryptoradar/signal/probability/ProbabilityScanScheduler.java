package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.service.CandleClient;
import com.cryptoradar.signal.service.SignalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hourly shadow scan: for every symbol in the current overview, synthesize one
 * ATR-geometry candidate (direction from the dimension-score sign), estimate its
 * win probability two ways (calibrated stats model + LLM overlay), and persist it
 * as a PENDING shadow candidate. Places no orders — this only collects the data
 * and predictions that the calibration report and Phase 2 model will use.
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
    @Inject ProbabilityCandidateRepository repository;
    @Inject ObjectMapper mapper;

    @Scheduled(every = "{probability.scan.interval:1h}", delayed = "90s", identity = "probability-scan")
    void scan() {
        List<TradingSignal> signals = signalService.getSignalOverview().getSignals();
        int persisted = 0;
        for (TradingSignal signal : signals) {
            try {
                if (scanSymbol(signal)) persisted++;
            } catch (RuntimeException e) {
                LOG.warnf("Probability scan failed for %s: %s", signal.getSymbol(), e.getMessage());
            }
        }
        LOG.infof("Probability scan complete — %d/%d candidates persisted", persisted, signals.size());
    }

    @Transactional
    boolean scanSymbol(TradingSignal signal) {
        String symbol = signal.getSymbol();
        List<CandleBar> bars = candleClient.fetchRecent(symbol, CANDLE_INTERVAL, CANDLE_LIMIT);
        if (bars.size() < ATR_PERIOD + 1) {
            LOG.debugf("Skipping %s — insufficient candles (%d)", symbol, bars.size());
            return false;
        }
        double atr = atr(bars);
        double entry = bars.get(bars.size() - 1).close();
        if (atr <= 0 || entry <= 0) return false;

        Map<String, Double> dimScores = dimensionScores(signal);
        String direction = signal.getOverallScore() >= 0 ? Candidate.LONG : Candidate.SHORT;
        Candidate candidate = CandidateBuilder.build(direction, entry, atr);

        double statsProb = estimator.statsProbability(dimScores);
        Optional<GeminiProbabilityClient.LlmEstimate> llm =
                estimator.llmProbability(buildPrompt(symbol, candidate, signal, dimScores));

        persist(symbol, candidate, statsProb, llm, dimScores, signal);
        return true;
    }

    private void persist(String symbol, Candidate candidate, double statsProb,
                         Optional<GeminiProbabilityClient.LlmEstimate> llm,
                         Map<String, Double> dimScores, TradingSignal signal) {
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
        row.llmProb = llm.map(GeminiProbabilityClient.LlmEstimate::probability).orElse(null);
        row.llmReasoning = llm.map(GeminiProbabilityClient.LlmEstimate::reasoning).orElse(null);
        row.featuresJson = featuresJson(dimScores, signal, candidate);
        row.status = ProbabilityCandidate.STATUS_PENDING;
        repository.persist(row);
    }

    private Map<String, Double> dimensionScores(TradingSignal signal) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (DimensionScore dim : signal.getDimensions()) {
            scores.put(dim.name(), dim.score());
        }
        return scores;
    }

    private String featuresJson(Map<String, Double> dimScores, TradingSignal signal, Candidate candidate) {
        Map<String, Object> features = new LinkedHashMap<>(dimScores);
        features.put("overallScore", signal.getOverallScore());
        features.put("alignment", signal.getAlignment());
        features.put("atr", candidate.atr());
        features.put("atrPct", candidate.atr() / candidate.entry());
        try {
            return mapper.writeValueAsString(features);
        } catch (Exception e) {
            return "{}";
        }
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

    /** Simple-average ATR over the last ATR_PERIOD true ranges. */
    private double atr(List<CandleBar> bars) {
        double sum = 0;
        int count = 0;
        for (int i = bars.size() - ATR_PERIOD; i < bars.size(); i++) {
            CandleBar cur = bars.get(i);
            CandleBar prev = bars.get(i - 1);
            double tr = Math.max(cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
