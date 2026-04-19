package com.cryptoradar.signal.util;

import java.util.Map;

/**
 * Defensive extraction helpers for reading typed values out of the
 * {@code Map<String, Object>} payloads that upstream services return.
 *
 * <p>Detectors use these to avoid scattering ugly {@code instanceof}
 * pattern checks through their business logic.
 */
public final class ContextValues {

    private ContextValues() {
    }

    /** Returns {@code null} if the value is missing or not a number. */
    public static Double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /** Returns {@code defaultValue} if the value is missing or not a number. */
    public static double asDoubleOr(Object value, double defaultValue) {
        Double result = asDouble(value);
        return result != null ? result : defaultValue;
    }

    /** Safe cast for nested map payloads. Returns an empty map if not a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /** Returns {@code null} if the map or key is missing. */
    public static Double readDouble(Map<String, Object> source, String key) {
        if (source == null) return null;
        return asDouble(source.get(key));
    }

    /** Returns the value of the named dimension, or 0.0 if missing. */
    public static double dimensionScore(Map<String, Double> scores, String dimensionName) {
        if (scores == null) return 0.0;
        Double value = scores.get(dimensionName);
        return value != null ? value : 0.0;
    }
}
