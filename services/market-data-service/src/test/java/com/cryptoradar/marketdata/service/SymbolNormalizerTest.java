package com.cryptoradar.marketdata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolNormalizerTest {

    @Test
    @DisplayName("Already-canonical symbol passes through unchanged")
    void canonicalSymbolPassesThrough() {
        assertEquals(Optional.of("BTCUSDT"), SymbolNormalizer.normalize("BTCUSDT"));
    }

    @Test
    @DisplayName("Lower-case symbol uppercases")
    void lowerCaseUppercases() {
        assertEquals(Optional.of("BTCUSDT"), SymbolNormalizer.normalize("btcusdt"));
        assertEquals(Optional.of("ETHUSDT"), SymbolNormalizer.normalize("ethUSDT"));
    }

    @Test
    @DisplayName("Symbol without USDT suffix gets it appended")
    void missingSuffixAppended() {
        assertEquals(Optional.of("BTCUSDT"), SymbolNormalizer.normalize("BTC"));
        assertEquals(Optional.of("DOGEUSDT"), SymbolNormalizer.normalize("doge"));
    }

    @Test
    @DisplayName("Whitespace stripped")
    void whitespaceStripped() {
        assertEquals(Optional.of("BTCUSDT"), SymbolNormalizer.normalize("  BTC  "));
    }

    @Test
    @DisplayName("Symbols with special characters rejected")
    void invalidCharsRejected() {
        assertTrue(SymbolNormalizer.normalize("BTC-USDT").isEmpty());
        assertTrue(SymbolNormalizer.normalize("BTC.USDT").isEmpty());
        assertTrue(SymbolNormalizer.normalize("BTC/USDT").isEmpty());
    }

    @Test
    @DisplayName("Empty / null / blank input rejected")
    void emptyInputsRejected() {
        assertTrue(SymbolNormalizer.normalize(null).isEmpty());
        assertTrue(SymbolNormalizer.normalize("").isEmpty());
        assertTrue(SymbolNormalizer.normalize("   ").isEmpty());
    }

    @Test
    @DisplayName("Numeric-only base accepted (e.g. 1000PEPEUSDT)")
    void numericBaseAccepted() {
        assertEquals(Optional.of("1000PEPEUSDT"), SymbolNormalizer.normalize("1000PEPEUSDT"));
    }

    @Test
    @DisplayName("Empty base after USDT-strip rejected (just 'USDT' is not a valid symbol)")
    void usdtOnlyRejected() {
        // Regex requires [A-Z0-9]+ before USDT
        assertFalse(SymbolNormalizer.normalize("USDT").isPresent());
    }

    @Test
    @DisplayName("stripQuote removes trailing USDT")
    void stripQuoteRemovesSuffix() {
        assertEquals("BTC", SymbolNormalizer.stripQuote("BTCUSDT"));
        assertEquals("DOGE", SymbolNormalizer.stripQuote("DOGEUSDT"));
    }

    @Test
    @DisplayName("stripQuote leaves non-USDT symbol unchanged")
    void stripQuoteIdempotentWithoutSuffix() {
        assertEquals("BTC", SymbolNormalizer.stripQuote("BTC"));
        assertEquals("", SymbolNormalizer.stripQuote(""));
        assertEquals("", SymbolNormalizer.stripQuote(null));
    }
}
