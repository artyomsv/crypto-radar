package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.ClosedPnlV5;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the WS close-capture mapping: detecting a closing fill and turning it
 * into a {@link ClosedPnlV5} the reconciler can apply. The durable close source
 * when Bybit's closed-pnl REST endpoint lags (frozen for days on DEMO).
 */
class BybitV5WsClientCloseCaptureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void closingFillDetectedWhenClosedSizePositive() {
        assertTrue(BybitV5WsClient.isClosingFill(node(
                "{\"symbol\":\"BTCUSDT\",\"closedSize\":\"0.001\",\"execPrice\":\"49000\"}")));
    }

    @Test
    void openingFillNotDetectedAsClosing() {
        // An opening fill has closedSize 0 (or absent).
        assertFalse(BybitV5WsClient.isClosingFill(node(
                "{\"symbol\":\"BTCUSDT\",\"closedSize\":\"0\",\"execPrice\":\"50000\"}")));
        assertFalse(BybitV5WsClient.isClosingFill(node(
                "{\"symbol\":\"BTCUSDT\",\"execPrice\":\"50000\"}")));
    }

    @Test
    void mapsClosingFillToClosedPnlWithRealExitAndCloseFeeOnly() {
        JsonNode exec = node("{\"symbol\":\"BTCUSDT\",\"orderId\":\"oid-1\",\"side\":\"Buy\","
                + "\"execQty\":\"0.001\",\"execPrice\":\"49000\",\"closedPnl\":\"1.0\","
                + "\"execFee\":\"0.05\",\"closedSize\":\"0.001\",\"execTime\":\"1700000100000\"}");

        ClosedPnlV5 c = BybitV5WsClient.closingFillToClosedPnl(exec);

        assertEquals("BTCUSDT", c.symbol());
        assertEquals("oid-1", c.orderId());
        assertEquals("Buy", c.side());
        assertEquals("49000", c.avgExitPrice());   // real exit fill price
        assertEquals("1.0", c.closedPnl());
        assertEquals("0.05", c.closeFee());
        // Entry stays on the trade; open fee was charged at entry, not on this fill.
        assertNull(c.avgEntryPrice());
        assertNull(c.openFee());
    }
}
