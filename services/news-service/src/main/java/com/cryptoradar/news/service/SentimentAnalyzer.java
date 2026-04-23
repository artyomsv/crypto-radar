package com.cryptoradar.news.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

/**
 * Lexicon-based sentiment classifier. Not a replacement for a proper NLP
 * model, but good enough for directional aggregate sentiment over N articles
 * when the lexicon covers the common inflections the headlines use.
 *
 * <p>Matching is two-phase:
 * <ul>
 *   <li>exact word match for all entries (covers short roots like "ban")</li>
 *   <li>prefix match against roots of length ≥ 5 so the common -s/-ed/-ing
 *       inflections ("drops", "surged", "rising") count without manually
 *       enumerating every conjugation. "ban" / "win" etc. stay exact-only
 *       to avoid false positives on "banana" / "wind".</li>
 * </ul>
 *
 * <p>Pre-fix a 17+17 exact-match lexicon missed inflected forms — headlines
 * like "Bitcoin drops" or "Ethereum surged" scored 0 because only bare roots
 * were in the set. Combined with CoinDesk providing empty bodies for many
 * articles, the result was a uniform 0.0 sentiment across 9000+ articles.
 */
@ApplicationScoped
public class SentimentAnalyzer {

    private static final double EPSILON = 0.05;
    private static final int MIN_STEM_LENGTH_FOR_PREFIX = 5;

    private static final Set<String> POSITIVE_WORDS = Set.of(
            "surge", "surged", "surges", "surging",
            "rally", "rallied", "rallies", "rallying",
            "bullish",
            "gain", "gained", "gains", "gaining",
            "rise", "rises", "rising", "rose", "risen",
            "soar", "soared", "soars", "soaring",
            "breakout", "breakouts",
            "upgrade", "upgraded", "upgrades",
            "adoption", "adopted", "adopts",
            "partnership", "partnerships",
            "launch", "launched", "launches", "launching",
            "record", "records",
            "high", "highs", "all-time",
            "growth", "grows", "growing", "grew",
            "boom", "booming",
            "profit", "profits", "profitable",
            "win", "winning", "wins",
            "approved", "approval", "approves",
            "outperform", "outperformed", "outperforms",
            "milestone", "milestones"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "crash", "crashed", "crashes", "crashing",
            "bearish",
            "drop", "drops", "dropped", "dropping",
            "fall", "falls", "fell", "fallen", "falling",
            "plunge", "plunges", "plunged", "plunging",
            "hack", "hacked", "hacks", "hacking",
            "scam", "scams",
            "fraud", "fraudulent",
            "ban", "banned", "bans",
            "regulation", "regulations", "regulated",
            "lawsuit", "lawsuits", "sued",
            "dump", "dumped", "dumps", "dumping",
            "loss", "losses", "losing", "lost",
            "fear", "fears", "fearful",
            "sell-off", "selloff",
            "decline", "declined", "declines", "declining",
            "warning", "warnings", "warned",
            "liquidation", "liquidations", "liquidated",
            "delisted", "delisting",
            "exploit", "exploited", "exploits",
            "collapse", "collapsed", "collapses", "collapsing",
            "slump", "slumped", "slumps"
    );

    private static final List<String> POSITIVE_STEMS = longStems(POSITIVE_WORDS);
    private static final List<String> NEGATIVE_STEMS = longStems(NEGATIVE_WORDS);

    public SentimentResult analyze(String title, String body) {
        String text = ((title != null ? title : "") + " " + (body != null ? body : "")).toLowerCase();
        String[] words = text.split("[\\s,.;:!?()\\[\\]\"']+");

        int positiveCount = 0;
        int negativeCount = 0;
        int totalWords = 0;

        for (String word : words) {
            String cleaned = word.trim();
            if (cleaned.isEmpty()) continue;
            totalWords++;

            if (matchesPositive(cleaned)) {
                positiveCount++;
            } else if (matchesNegative(cleaned)) {
                negativeCount++;
            }
        }

        if (totalWords == 0) {
            return new SentimentResult(0.0, "neutral");
        }

        double score = (double) (positiveCount - negativeCount) / totalWords;
        score = Math.max(-1.0, Math.min(1.0, score));

        String label;
        if (score > EPSILON) {
            label = "positive";
        } else if (score < -EPSILON) {
            label = "negative";
        } else {
            label = "neutral";
        }

        return new SentimentResult(score, label);
    }

    private static boolean matchesPositive(String word) {
        if (POSITIVE_WORDS.contains(word)) return true;
        for (String stem : POSITIVE_STEMS) {
            if (word.startsWith(stem)) return true;
        }
        return false;
    }

    private static boolean matchesNegative(String word) {
        if (NEGATIVE_WORDS.contains(word)) return true;
        for (String stem : NEGATIVE_STEMS) {
            if (word.startsWith(stem)) return true;
        }
        return false;
    }

    private static List<String> longStems(Set<String> lexicon) {
        return lexicon.stream()
                .filter(w -> w.length() >= MIN_STEM_LENGTH_FOR_PREFIX)
                .filter(w -> !w.contains("-"))
                .distinct()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
    }
}
