package com.cryptoradar.signal.model;

import java.util.Map;

/**
 * Parameter object bundling all per-symbol raw data maps that the signal
 * engine and trade setup detectors consume.
 *
 * <p>Exists so the internal API can pass these together as one argument
 * instead of exploding method signatures to 5+ parameters.
 */
public record SymbolRawData(
        Map<String, Object> analytics,
        Map<String, Object> whaleData,
        Map<String, Object> derivativesData,
        Map<String, Object> priceData,
        Map<String, Object> macroData
) {
}
