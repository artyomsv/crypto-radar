package com.cryptoradar.whale.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhaleTransactionTest {

    @Test
    void fromBinanceTrade_buyerMaker_isSellSide() {
        // When isBuyerMaker=true, the aggressor is the seller -> SELL
        WhaleTransaction tx = WhaleTransaction.fromBinanceTrade(
                "BTCUSDT", 66800.0, 1.5, true, System.currentTimeMillis());

        assertEquals("SELL", tx.getSide());
        assertEquals("BTCUSDT", tx.getSymbol());
        assertEquals(66800.0, tx.getPrice());
        assertEquals(1.5, tx.getQuantity());
        assertEquals(66800.0 * 1.5, tx.getValueUsd(), 0.01);
        assertEquals("binance", tx.getSource());
    }

    @Test
    void fromBinanceTrade_sellerMaker_isBuySide() {
        // When isBuyerMaker=false, the aggressor is the buyer -> BUY
        WhaleTransaction tx = WhaleTransaction.fromBinanceTrade(
                "ETHUSDT", 2060.0, 30.0, false, System.currentTimeMillis());

        assertEquals("BUY", tx.getSide());
        assertEquals("ETHUSDT", tx.getSymbol());
        assertEquals(2060.0 * 30.0, tx.getValueUsd(), 0.01);
    }

    @Test
    void fromBinanceTrade_calculatesValueCorrectly() {
        WhaleTransaction tx = WhaleTransaction.fromBinanceTrade(
                "SOLUSDT", 80.0, 1000.0, false, 1712150400000L);

        assertEquals(80000.0, tx.getValueUsd(), 0.01);
        assertNotNull(tx.getTime());
    }

    @Test
    void fromBinanceTrade_setsTimestampFromTradeTime() {
        long tradeTimeMs = 1712150400000L; // fixed timestamp
        WhaleTransaction tx = WhaleTransaction.fromBinanceTrade(
                "BTCUSDT", 66800.0, 1.0, false, tradeTimeMs);

        assertNotNull(tx.getTime());
        assertEquals(tradeTimeMs, tx.getTime().toEpochMilli());
    }
}
