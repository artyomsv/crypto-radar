package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.ClosedPnlV5;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private ExecutedTrade openTrade(String symbol, String direction, String qty) {
        ExecutedTrade t = new ExecutedTrade();
        t.setSymbol(symbol);
        t.setDirection(direction);
        t.setQty(new BigDecimal(qty));
        t.setStatus(TradeStatus.OPEN);
        return t;
    }

    @Test
    void picksTradeWhoseQtyMatchesClosedSize() {
        // Two same-direction SHORTs on one symbol (the BCH #263 / #275 case).
        // A closing fill must land on the trade whose qty matches the fill size,
        // not blindly on the first symbol+direction match — otherwise one trade's
        // PnL is attributed to the other and the sibling is left blank.
        ExecutedTrade small = openTrade("BCHUSDT", "SHORT", "0.73");
        ExecutedTrade large = openTrade("BCHUSDT", "SHORT", "6.66");
        List<ExecutedTrade> open = List.of(small, large);

        assertSame(large, BybitV5WsClient.pickTradeForClosingFill(open, "BCHUSDT", "SHORT", new BigDecimal("6.66")));
        assertSame(small, BybitV5WsClient.pickTradeForClosingFill(open, "BCHUSDT", "SHORT", new BigDecimal("0.73")));
    }

    @Test
    void skipsSoleMatchWhenQtyFarFromFill() {
        // The actual BCH bug: the fill's real trade (qty 6.66) already closed, so
        // the only remaining open SHORT is the tiny donchian one (qty 0.73).
        // Attributing the 6.66 fill to it would copy the sibling's PnL — must skip.
        ExecutedTrade tiny = openTrade("BCHUSDT", "SHORT", "0.73");
        List<ExecutedTrade> open = List.of(tiny);
        assertNull(BybitV5WsClient.pickTradeForClosingFill(open, "BCHUSDT", "SHORT", new BigDecimal("6.66")));
    }

    @Test
    void picksSoleMatchWhenQtyWithinTolerance() {
        // Full close (qty ≈ fill) is the common case and must still attribute.
        ExecutedTrade only = openTrade("ETHUSDT", "LONG", "1.50");
        List<ExecutedTrade> open = List.of(only);
        assertSame(only, BybitV5WsClient.pickTradeForClosingFill(open, "ETHUSDT", "LONG", new BigDecimal("1.50")));
    }

    @Test
    void excludesWrongSymbolOrDirectionAndReturnsNullWhenNoMatch() {
        List<ExecutedTrade> open = List.of(
                openTrade("BTCUSDT", "LONG", "0.1"),
                openTrade("ETHUSDT", "SHORT", "1.0"));
        assertNull(BybitV5WsClient.pickTradeForClosingFill(open, "BTCUSDT", "SHORT", new BigDecimal("0.1")));
        assertNull(BybitV5WsClient.pickTradeForClosingFill(open, "SOLUSDT", "LONG", new BigDecimal("1.0")));
    }
}
