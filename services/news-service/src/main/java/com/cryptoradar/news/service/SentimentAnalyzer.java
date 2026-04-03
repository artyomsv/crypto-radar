package com.cryptoradar.news.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

@ApplicationScoped
public class SentimentAnalyzer {

    private static final Set<String> POSITIVE_WORDS = Set.of(
            "surge", "rally", "bullish", "gain", "rise", "soar", "breakout",
            "upgrade", "adoption", "partnership", "launch", "record", "high",
            "growth", "boom", "profit", "win"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "crash", "bearish", "drop", "fall", "plunge", "hack", "scam",
            "fraud", "ban", "regulation", "lawsuit", "dump", "loss", "fear",
            "sell-off", "decline", "warning"
    );

    public SentimentResult analyze(String title, String body) {
        String text = ((title != null ? title : "") + " " + (body != null ? body : "")).toLowerCase();
        String[] words = text.split("[\\s,.;:!?()\\[\\]\"']+");

        int positiveCount = 0;
        int negativeCount = 0;
        int totalWords = 0;

        for (String word : words) {
            String cleaned = word.trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            totalWords++;

            if (POSITIVE_WORDS.contains(cleaned)) {
                positiveCount++;
            } else if (NEGATIVE_WORDS.contains(cleaned)) {
                negativeCount++;
            }
        }

        if (totalWords == 0) {
            return new SentimentResult(0.0, "neutral");
        }

        double score = (double) (positiveCount - negativeCount) / totalWords;
        score = Math.max(-1.0, Math.min(1.0, score));

        String label;
        if (score > 0.05) {
            label = "positive";
        } else if (score < -0.05) {
            label = "negative";
        } else {
            label = "neutral";
        }

        return new SentimentResult(score, label);
    }
}
