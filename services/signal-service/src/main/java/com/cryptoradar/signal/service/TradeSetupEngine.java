package com.cryptoradar.signal.service;

import com.cryptoradar.signal.detector.TradeSetupDetector;
import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.SymbolRawData;
import com.cryptoradar.signal.model.TradeSetup;
import com.cryptoradar.signal.util.ContextValues;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates {@link TradeSetupDetector}s for a single symbol per cycle.
 *
 * <p>The engine's only job is to assemble a {@link MarketContext} (fetching
 * 4h candles, extracting dimension scores, carrying raw data forward) and
 * then run each registered detector against it. It does not score, combine,
 * or rank detector outputs — each setup flows directly to the outcome
 * tracker for independent measurement.
 *
 * <p>New detectors are auto-registered: drop a CDI bean implementing
 * {@link TradeSetupDetector} into the {@code detector} package and it will
 * be picked up without any wiring changes here.
 */
@ApplicationScoped
public class TradeSetupEngine {

    private static final Logger LOG = Logger.getLogger(TradeSetupEngine.class);

    private static final String INTERVAL_4H = "4h";
    private static final int BARS_TO_FETCH = 10;
    private static final String PRICE_FIELD = "price";

    private final DataAggregator dataAggregator;
    private final DonchianChannelService donchianService;
    private final Instance<TradeSetupDetector> detectors;

    public TradeSetupEngine(
            DataAggregator dataAggregator,
            DonchianChannelService donchianService,
            @Any Instance<TradeSetupDetector> detectors) {
        this.dataAggregator = dataAggregator;
        this.donchianService = donchianService;
        this.detectors = detectors;
    }

    /**
     * Evaluates all detectors for a single symbol. Returns an empty list if
     * nothing fired. Never throws — detector failures are logged and skipped
     * so one broken detector can't take down the whole cycle.
     */
    public List<TradeSetup> detectForSymbol(String symbol, SymbolRawData data, List<DimensionScore> dimensions) {
        MarketContext context = buildContext(symbol, data, dimensions);

        List<TradeSetup> results = new ArrayList<>();
        for (TradeSetupDetector detector : detectors) {
            runDetector(detector, context).ifPresent(results::add);
        }
        return results;
    }

    private Optional<TradeSetup> runDetector(TradeSetupDetector detector, MarketContext context) {
        try {
            return detector.detect(context);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Detector %s failed for %s — skipping",
                    detector.name(), context.symbol());
            return Optional.empty();
        }
    }

    private MarketContext buildContext(String symbol, SymbolRawData data, List<DimensionScore> dimensions) {
        Double currentPrice = ContextValues.readDouble(data.priceData(), PRICE_FIELD);
        Map<String, Double> dimensionScores = toDimensionScores(dimensions);
        List<CandleBar> bars = loadRecentBars(symbol);
        DonchianSnapshot donchian = donchianService.snapshotFor(symbol).orElse(null);

        return new MarketContext(
                symbol,
                currentPrice,
                nullSafe(data.analytics()),
                nullSafe(data.whaleData()),
                nullSafe(data.derivativesData()),
                nullSafe(data.macroData()),
                dimensionScores,
                bars,
                donchian);
    }

    private Map<String, Double> toDimensionScores(List<DimensionScore> dimensions) {
        Map<String, Double> scores = new HashMap<>();
        if (dimensions == null) return scores;
        for (DimensionScore dim : dimensions) {
            scores.put(dim.name(), dim.score());
        }
        return scores;
    }

    private List<CandleBar> loadRecentBars(String symbol) {
        List<Map<String, Object>> raw = dataAggregator.fetchCandles(symbol, INTERVAL_4H, BARS_TO_FETCH);
        if (raw == null || raw.isEmpty()) return List.of();

        List<CandleBar> bars = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            CandleBar bar = toBar(row);
            if (bar != null) bars.add(bar);
        }
        // market-data-service returns newest-first; reverse to oldest-first
        // so detector logic can walk time naturally.
        java.util.Collections.reverse(bars);
        return bars;
    }

    private CandleBar toBar(Map<String, Object> row) {
        Object timeObj = row.get("time");
        if (timeObj == null) return null;
        Double open  = ContextValues.readDouble(row, "open");
        Double high  = ContextValues.readDouble(row, "high");
        Double low   = ContextValues.readDouble(row, "low");
        Double close = ContextValues.readDouble(row, "close");
        if (open == null || high == null || low == null || close == null) return null;
        try {
            Instant time = Instant.parse(timeObj.toString());
            return new CandleBar(time, open, high, low, close);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> nullSafe(Map<String, Object> source) {
        return source != null ? source : Map.of();
    }
}
