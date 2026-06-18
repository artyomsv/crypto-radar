package com.cryptoradar.derivatives.provider;

/**
 * Normalizes per-exchange liquidation fields into one consistent convention so
 * the pooled {@code liquidations} table is comparable across venues.
 *
 * <p>Two things differ between exchanges and must be reconciled:
 *
 * <ol>
 *   <li><b>Side semantics.</b> Binance and OKX report the liquidation <i>order</i>
 *       side — a long position is force-<b>SOLD</b>, so {@code SELL} means a long was
 *       liquidated. Bybit's {@code allLiquidation} reports the <i>position</i> side
 *       directly — {@code Buy} means a long was liquidated. We store the liquidated
 *       <b>position</b> side ({@link #LONG}/{@link #SHORT}) regardless of venue.</li>
 *   <li><b>Size units.</b> Binance ({@code q}) and Bybit linear ({@code v}) report size
 *       in the base asset. OKX ({@code sz}) reports size in <i>contracts</i>, which must
 *       be multiplied by the instrument's contract value to get base-asset quantity.</li>
 * </ol>
 */
public final class LiquidationNormalizer {

    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";

    public static final String BINANCE = "BINANCE";
    public static final String OKX = "OKX";
    public static final String BYBIT = "BYBIT";

    private LiquidationNormalizer() {}

    /**
     * Maps an exchange's raw side string to the liquidated position side.
     * Bybit uses position-side semantics; all other venues use order-side.
     * Unknown inputs are returned upper-cased so corruption is visible, not silent.
     */
    public static String liquidatedSide(String exchange, String rawSide) {
        String side = rawSide == null ? "" : rawSide.trim().toUpperCase();
        if (BYBIT.equalsIgnoreCase(exchange)) {
            if ("BUY".equals(side)) return LONG;
            if ("SELL".equals(side)) return SHORT;
            return side;
        }
        // Binance / OKX order-side convention: a long is closed by a SELL.
        if ("SELL".equals(side)) return LONG;
        if ("BUY".equals(side)) return SHORT;
        return side;
    }

    /**
     * Converts an OKX contract count into base-asset quantity using the
     * instrument's contract value and multiplier (e.g. BTC-USDT-SWAP: ctVal=0.01,
     * ctMult=1 → 1 contract = 0.01 BTC).
     */
    public static double contractsToBaseQty(double contracts, double ctVal, double ctMult) {
        return contracts * ctVal * ctMult;
    }
}
