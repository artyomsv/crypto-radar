package com.cryptoradar.whale.model;

import java.time.Instant;

public class WhaleTransaction {

    private Instant time;
    private String symbol;
    private double price;
    private double quantity;
    private double valueUsd;
    private String side;
    private String source;
    private String tradeId;
    private String fromAddress;
    private String toAddress;
    private String fromLabel;
    private String toLabel;
    private String blockchain;
    private String txHash;

    public WhaleTransaction() {
    }

    public WhaleTransaction(Instant time, String symbol, double price, double quantity,
                            double valueUsd, String side, String source, String tradeId,
                            String fromAddress, String toAddress, String fromLabel,
                            String toLabel, String blockchain, String txHash) {
        this.time = time;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.valueUsd = valueUsd;
        this.side = side;
        this.source = source;
        this.tradeId = tradeId;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.fromLabel = fromLabel;
        this.toLabel = toLabel;
        this.blockchain = blockchain;
        this.txHash = txHash;
    }

    /**
     * Create a WhaleTransaction from Binance aggTrade data.
     *
     * @param symbol      trading pair (e.g. BTCUSDT)
     * @param price       trade price
     * @param quantity    trade quantity
     * @param isBuyerMaker true if buyer is maker (= sell-side initiated trade)
     * @param tradeTimeMs trade timestamp in epoch millis
     */
    public static WhaleTransaction fromBinanceTrade(String symbol, double price,
                                                     double quantity, boolean isBuyerMaker,
                                                     long tradeTimeMs) {
        // When buyer is maker, the trade was initiated by a seller = SELL pressure
        String side = isBuyerMaker ? "SELL" : "BUY";
        double valueUsd = price * quantity;
        Instant time = Instant.ofEpochMilli(tradeTimeMs);

        return new WhaleTransaction(time, symbol, price, quantity, valueUsd, side,
                "binance", null, null, null, null, null, null, null);
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public double getValueUsd() {
        return valueUsd;
    }

    public void setValueUsd(double valueUsd) {
        this.valueUsd = valueUsd;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTradeId() {
        return tradeId;
    }

    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getToAddress() {
        return toAddress;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
    }

    public String getFromLabel() {
        return fromLabel;
    }

    public void setFromLabel(String fromLabel) {
        this.fromLabel = fromLabel;
    }

    public String getToLabel() {
        return toLabel;
    }

    public void setToLabel(String toLabel) {
        this.toLabel = toLabel;
    }

    public String getBlockchain() {
        return blockchain;
    }

    public void setBlockchain(String blockchain) {
        this.blockchain = blockchain;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }
}
