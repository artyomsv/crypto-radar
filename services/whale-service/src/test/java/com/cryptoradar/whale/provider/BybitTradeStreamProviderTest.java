package com.cryptoradar.whale.provider;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.bybit.BybitTradeStreamProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link BybitTradeStreamProvider#parseTradeMessage(String)}.
 *
 * <p>The parsing logic silently returns {@code null} on any error. Without
 * tests, an exchange format change is invisible until we notice "zero whale
 * trades for symbol X" days later. These tests pin the v5 Bybit publicTrade
 * frame shape so a future field rename surfaces immediately.
 */
class BybitTradeStreamProviderTest {

    private BybitTradeStreamProvider provider;
    private Method parseTradeMessage;

    @BeforeEach
    void setUp() throws Exception {
        provider = new BybitTradeStreamProvider();
        // The base class injects ObjectMapper via @Inject; outside CDI we
        // wire it reflectively. Same pattern as Quarkus tests that touch
        // abstract providers without booting the runtime.
        Field mapperField = findField(provider.getClass(), "objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(provider, new ObjectMapper());
        parseTradeMessage = provider.getClass().getDeclaredMethod("parseTradeMessage", String.class);
        parseTradeMessage.setAccessible(true);
    }

    @Test
    @DisplayName("Whale trade above threshold returns populated WhaleTransaction")
    void parseWhaleTrade() throws Exception {
        String msg = """
            {
              "topic": "publicTrade.BTCUSDT",
              "data": [{
                "s": "BTCUSDT",
                "p": "50000",
                "v": "1.0",
                "S": "Buy",
                "T": 1700000000000
              }]
            }""";

        WhaleTransaction tx = (WhaleTransaction) parseTradeMessage.invoke(provider, msg);

        assertNotNull(tx);
        assertEquals("BTCUSDT", tx.getSymbol());
        assertEquals(50000.0, tx.getPrice());
        assertEquals(1.0, tx.getQuantity());
        assertEquals(50000.0, tx.getValueUsd());
        assertEquals("BUY", tx.getSide());
        assertEquals("bybit", tx.getSource());
    }

    @Test
    @DisplayName("Trade below tier-high threshold ($5000 for BTC) returns null")
    void belowThresholdReturnsNull() throws Exception {
        // $50 trade — well below the $5000 BTC threshold
        String msg = """
            {
              "topic": "publicTrade.BTCUSDT",
              "data": [{
                "s": "BTCUSDT",
                "p": "50000",
                "v": "0.001",
                "S": "Sell",
                "T": 1700000000000
              }]
            }""";

        Object tx = parseTradeMessage.invoke(provider, msg);

        assertNull(tx);
    }

    @Test
    @DisplayName("Sell-side trade maps to SELL")
    void sellSideMapping() throws Exception {
        String msg = """
            {
              "topic": "publicTrade.ETHUSDT",
              "data": [{
                "s": "ETHUSDT",
                "p": "3000",
                "v": "2.0",
                "S": "Sell",
                "T": 1700000000000
              }]
            }""";

        WhaleTransaction tx = (WhaleTransaction) parseTradeMessage.invoke(provider, msg);

        assertNotNull(tx);
        assertEquals("SELL", tx.getSide());
    }

    @Test
    @DisplayName("Non-publicTrade topic returns null (subscribe-confirm, ping-pong)")
    void nonPublicTradeTopicReturnsNull() throws Exception {
        String msg = """
            {
              "topic": "orderbook.50.BTCUSDT",
              "data": [{"s":"BTCUSDT","p":"50000","v":"1.0","S":"Buy","T":1700000000000}]
            }""";

        Object tx = parseTradeMessage.invoke(provider, msg);

        assertNull(tx);
    }

    @Test
    @DisplayName("Empty data array returns null")
    void emptyDataReturnsNull() throws Exception {
        String msg = """
            { "topic": "publicTrade.BTCUSDT", "data": [] }""";

        Object tx = parseTradeMessage.invoke(provider, msg);

        assertNull(tx);
    }

    @Test
    @DisplayName("Malformed JSON returns null (no throw)")
    void malformedJsonReturnsNull() throws Exception {
        Object tx = parseTradeMessage.invoke(provider, "not json {{");
        assertNull(tx);
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
        }
        throw new IllegalStateException("field " + name + " not found in class hierarchy");
    }
}
