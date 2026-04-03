package com.cryptoradar.news.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts cryptocurrency symbols mentioned in text.
 * Shared by all news providers for consistent symbol tagging.
 */
public final class SymbolExtractor {

    private static final Map<String, String> KEYWORDS_TO_SYMBOL = Map.ofEntries(
            Map.entry("bitcoin", "BTC"), Map.entry("btc", "BTC"),
            Map.entry("ethereum", "ETH"), Map.entry("eth", "ETH"),
            Map.entry("binance", "BNB"), Map.entry("bnb", "BNB"),
            Map.entry("solana", "SOL"), Map.entry("sol", "SOL"),
            Map.entry("ripple", "XRP"), Map.entry("xrp", "XRP"),
            Map.entry("cardano", "ADA"), Map.entry("ada", "ADA"),
            Map.entry("avalanche", "AVAX"), Map.entry("avax", "AVAX"),
            Map.entry("polkadot", "DOT"), Map.entry("dot", "DOT"),
            Map.entry("chainlink", "LINK"), Map.entry("link", "LINK"),
            Map.entry("dogecoin", "DOGE"), Map.entry("doge", "DOGE")
    );

    private SymbolExtractor() {}

    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        String lower = text.toLowerCase();
        List<String> symbols = new ArrayList<>();
        for (Map.Entry<String, String> entry : KEYWORDS_TO_SYMBOL.entrySet()) {
            if (lower.contains(entry.getKey()) && !symbols.contains(entry.getValue())) {
                symbols.add(entry.getValue());
            }
        }
        return symbols;
    }
}
