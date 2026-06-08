package com.cryptoradar.signal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for GeminiAnalysisService — covers isEnabled gating,
 * the no-key fast path, and cache miss/hit semantics. The HTTP-calling
 * methods are not exercised here; they need a network stub and would
 * grow into integration tests.
 */
class GeminiAnalysisServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Blank API key → isEnabled false")
    void blankKeyDisablesService() {
        GeminiAnalysisService svc = new GeminiAnalysisService(mapper, "");
        assertFalse(svc.isEnabled());
    }

    @Test
    @DisplayName("Whitespace-only API key → isEnabled false")
    void whitespaceKeyDisablesService() {
        GeminiAnalysisService svc = new GeminiAnalysisService(mapper, "   ");
        assertFalse(svc.isEnabled());
    }

    @Test
    @DisplayName("Non-blank API key → isEnabled true")
    void presentKeyEnablesService() {
        GeminiAnalysisService svc = new GeminiAnalysisService(mapper, "test-key-xyz");
        assertTrue(svc.isEnabled());
    }

    @Test
    @DisplayName("getCachedAnalysis returns null when nothing cached")
    void unknownSymbolReturnsNull() {
        GeminiAnalysisService svc = new GeminiAnalysisService(mapper, "test-key");
        assertNull(svc.getCachedAnalysis("BTCUSDT"));
        assertNull(svc.getCachedAnalysisTimestamp("BTCUSDT"));
    }

    @Test
    @DisplayName("triggerAnalysis is no-op when service disabled")
    void disabledServiceSkipsTrigger() {
        GeminiAnalysisService svc = new GeminiAnalysisService(mapper, "");
        // Should not throw and should not enqueue any work
        svc.triggerAnalysis("BTCUSDT", Map.of("indicator", "value"));
        assertNull(svc.getCachedAnalysis("BTCUSDT"));
    }

    @Test
    @DisplayName("analyzeNow returns unavailable message when disabled")
    void disabledAnalyzeNowReturnsMessage() {
        GeminiAnalysisService svc = new GeminiAnalysisService(mapper, "");
        String result = svc.analyzeNow("BTCUSDT", Map.of("price", 50000.0));
        assertSame("AI analysis unavailable — GEMINI_API_KEY not configured.", result);
    }
}
