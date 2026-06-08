package com.cryptoradar.marketdata.service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Single-purpose helper for the symbol-normalization rules used by the
 * crypto-config CRUD endpoints. Pure functions — package-private so the
 * resource's add/toggle/delete paths can delegate without owning the
 * regex + suffix logic inline.
 *
 * <p>The canonical contract for projectr-x is:
 * <ul>
 *   <li>Upper-case the input</li>
 *   <li>Append {@code USDT} if not present</li>
 *   <li>Accept only {@code [A-Z0-9]+USDT}</li>
 * </ul>
 *
 * <p>Centralising here means the rule is testable in isolation and a
 * future shift (e.g. allow USDC pairs) lives in one file.
 */
public final class SymbolNormalizer {

    private static final Pattern CANONICAL_PATTERN = Pattern.compile("[A-Z0-9]+USDT");
    private static final String QUOTE_SUFFIX = "USDT";

    private SymbolNormalizer() {}

    /**
     * Returns the canonicalized symbol when the input can be normalized to
     * a valid {@code [A-Z0-9]+USDT} form; empty otherwise.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return Optional.empty();
        String upper = trimmed.toUpperCase();
        String withSuffix = upper.endsWith(QUOTE_SUFFIX) ? upper : upper + QUOTE_SUFFIX;
        return CANONICAL_PATTERN.matcher(withSuffix).matches()
                ? Optional.of(withSuffix)
                : Optional.empty();
    }

    /**
     * Removes the canonical {@code USDT} quote suffix. Used to derive a
     * default human-readable name when none is supplied.
     */
    public static String stripQuote(String symbol) {
        if (symbol == null) return "";
        return symbol.endsWith(QUOTE_SUFFIX)
                ? symbol.substring(0, symbol.length() - QUOTE_SUFFIX.length())
                : symbol;
    }
}
