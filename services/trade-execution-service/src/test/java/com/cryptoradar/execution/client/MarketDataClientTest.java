package com.cryptoradar.execution.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-unit coverage of {@link MarketDataClient#parsePrice}. The HTTP
 * call itself is exercised by integration smoke; what mattered for the
 * v2-trail-dead bug was the JSON shape, not the transport.
 */
class MarketDataClientTest {

    private MarketDataClient client;

    @BeforeEach
    void setUp() {
        client = new MarketDataClient();
        client.mapper = new ObjectMapper();
    }

    @Test
    void parsesPriceFromArrayShape() {
        String body = "[{\"time\":\"2026-04-29T21:30:10Z\",\"symbol\":\"BTCUSDT\",\"price\":77728.15},"
                + "{\"time\":\"2026-04-29T21:30:10Z\",\"symbol\":\"ETHUSDT\",\"price\":2353.38}]";
        assertEquals(0, new BigDecimal("77728.15").compareTo(client.parsePrice(body, "BTCUSDT")));
        assertEquals(0, new BigDecimal("2353.38").compareTo(client.parsePrice(body, "ETHUSDT")));
    }

    @Test
    void returnsNullWhenSymbolNotInList() {
        String body = "[{\"symbol\":\"BTCUSDT\",\"price\":77728.15}]";
        assertNull(client.parsePrice(body, "DOGEUSDT"));
    }

    @Test
    void returnsNullForLegacyObjectShape() {
        // Legacy code expected a symbol-keyed object — nothing in the
        // current API ever produces this, but if a downstream regression
        // re-introduces it we don't want to silently succeed against the
        // wrong shape.
        String body = "{\"BTCUSDT\":{\"price\":77728.15}}";
        assertNull(client.parsePrice(body, "BTCUSDT"));
    }

    @Test
    void returnsNullOnMalformedBody() {
        assertNull(client.parsePrice("not json", "BTCUSDT"));
        assertNull(client.parsePrice("", "BTCUSDT"));
    }

    @Test
    void returnsNullWhenPriceFieldMissing() {
        String body = "[{\"symbol\":\"BTCUSDT\"}]";
        assertNull(client.parsePrice(body, "BTCUSDT"));
    }
}
