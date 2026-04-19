package com.cryptoradar.signal.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything a {@code TradeSetupDetector} needs to evaluate a symbol in one
 * shot. Constructed once per symbol per cycle by the engine; detectors are
 * pure consumers that never fetch data themselves.
 *
 * <p>This separation of concerns is the key to keeping detectors testable:
 * a unit test can construct a {@code MarketContext} from a fixture map and
 * exercise the full detection logic with zero I/O.
 *
 * @param symbol            trading pair, e.g. {@code "BTCUSDT"}
 * @param currentPrice      latest tick price, nullable if missing
 * @param analytics         raw response from analytics-service
 * @param whaleData         per-symbol whale analytics
 * @param derivativesData   per-symbol derivatives snapshot
 * @param macroData         global macro data
 * @param dimensionScores   name → score map computed by {@code SignalEngine},
 *                          used by detectors as a confluence filter
 * @param recent4hBars      last N closed 4h bars, oldest first; may be empty
 *                          if market-data-service was unreachable
 */
public record MarketContext(
        String symbol,
        Double currentPrice,
        Map<String, Object> analytics,
        Map<String, Object> whaleData,
        Map<String, Object> derivativesData,
        Map<String, Object> macroData,
        Map<String, Double> dimensionScores,
        List<CandleBar> recent4hBars
) {
    public static MarketContext empty(String symbol) {
        return new MarketContext(symbol, null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList());
    }
}
