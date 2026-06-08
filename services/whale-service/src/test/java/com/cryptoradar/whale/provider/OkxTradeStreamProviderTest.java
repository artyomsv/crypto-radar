package com.cryptoradar.whale.provider;

import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.okx.OkxTradeStreamProvider;
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
 * Unit tests for {@link OkxTradeStreamProvider#parseTradeMessage(String)}.
 *
 * <p>The OKX provider has the unique extra concern of an instrument-ID
 * remap ({@code BTC-USDT → BTCUSDT}). If that map gets a wrong key, the
 * provider silently drops every trade for that pair. These tests pin the
 * canonical mapping and the v5 OKX trades-channel shape.
 */
class OkxTradeStreamProviderTest {

    private OkxTradeStreamProvider provider;
    private Method parseTradeMessage;

    @BeforeEach
    void setUp() throws Exception {
        provider = new OkxTradeStreamProvider();
        Field mapperField = findField(provider.getClass(), "objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(provider, new ObjectMapper());
        parseTradeMessage = provider.getClass().getDeclaredMethod("parseTradeMessage", String.class);
        parseTradeMessage.setAccessible(true);
    }

    @Test
    @DisplayName("BTC-USDT → BTCUSDT canonical remap")
    void btcDashUsdtRemapsToBtcUsdt() throws Exception {
        String msg = """
            {
              "arg": {"channel":"trades","instId":"BTC-USDT"},
              "data": [{
                "instId": "BTC-USDT",
                "px": "50000",
                "sz": "1.0",
                "side": "buy",
                "ts": "1700000000000"
              }]
            }""";

        WhaleTransaction tx = (WhaleTransaction) parseTradeMessage.invoke(provider, msg);

        assertNotNull(tx);
        assertEquals("BTCUSDT", tx.getSymbol(), "instId BTC-USDT must remap to canonical BTCUSDT");
        assertEquals("okx", tx.getSource());
        assertEquals(50000.0, tx.getValueUsd());
    }

    @Test
    @DisplayName("Unmapped instId is silently skipped (no map entry)")
    void unmappedInstIdSkipped() throws Exception {
        String msg = """
            {
              "data": [{
                "instId": "RANDOM-TOKEN-USDT",
                "px": "100",
                "sz": "1000.0",
                "side": "buy",
                "ts": "1700000000000"
              }]
            }""";

        Object tx = parseTradeMessage.invoke(provider, msg);

        assertNull(tx);
    }

    @Test
    @DisplayName("OKX side='buy' → BUY; side='sell' → SELL")
    void okxSideMapping() throws Exception {
        String sellMsg = """
            {
              "data": [{
                "instId": "ETH-USDT",
                "px": "3000",
                "sz": "10.0",
                "side": "sell",
                "ts": "1700000000000"
              }]
            }""";

        WhaleTransaction tx = (WhaleTransaction) parseTradeMessage.invoke(provider, sellMsg);

        assertNotNull(tx);
        assertEquals("SELL", tx.getSide());
        assertEquals("ETHUSDT", tx.getSymbol());
    }

    @Test
    @DisplayName("Mid-tier symbol (LTCUSDT) uses $1000 threshold — $50 trade dropped")
    void midTierBelowThresholdReturnsNull() throws Exception {
        // LTC is in TIER_MID → $1000 threshold. $50 → drop.
        String msg = """
            {
              "data": [{
                "instId": "LTC-USDT",
                "px": "100",
                "sz": "0.5",
                "side": "buy",
                "ts": "1700000000000"
              }]
            }""";

        Object tx = parseTradeMessage.invoke(provider, msg);
        assertNull(tx);
    }

    @Test
    @DisplayName("Top-tier symbol above $5000 threshold returns valid transaction")
    void topTierAboveThresholdReturnsValid() throws Exception {
        String msg = """
            {
              "data": [{
                "instId": "BTC-USDT",
                "px": "50000",
                "sz": "0.2",
                "side": "buy",
                "ts": "1700000000000"
              }]
            }""";
        // $10,000 trade — above the $5000 BTC threshold.

        WhaleTransaction tx = (WhaleTransaction) parseTradeMessage.invoke(provider, msg);

        assertNotNull(tx);
        assertEquals(10000.0, tx.getValueUsd());
    }

    @Test
    @DisplayName("Missing data array returns null")
    void missingDataReturnsNull() throws Exception {
        String msg = """
            { "event":"subscribe", "arg":{"channel":"trades","instId":"BTC-USDT"} }""";

        Object tx = parseTradeMessage.invoke(provider, msg);
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
