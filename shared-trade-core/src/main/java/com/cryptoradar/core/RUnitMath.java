package com.cryptoradar.core;

/**
 * Position-sizing helpers. R-multiple accounting: risk is {@code equity × riskPercent/100},
 * and position quantity is that dollar risk divided by the entry-to-stop distance.
 *
 * <p>{@code riskPercent} is expressed as a whole-number percent (1.0 = 1%, not 0.01).
 */
public final class RUnitMath {

    private RUnitMath() {}

    /**
     * Compute order quantity from equity, risk %, entry price, stop price, and
     * exchange lot step. Result is floored to the lot step so the returned qty
     * is always a valid submittable size.
     *
     * @param equity       account equity in quote currency (USDT)
     * @param riskPercent  percent of equity to risk on this trade (1.0 = 1%)
     * @param entryPrice   planned entry price
     * @param stopPrice    planned stop price (absolute, any side)
     * @param lotSize      exchange-defined quantity step (e.g., 0.001 for BTC)
     * @return qty rounded down to the nearest multiple of lotSize
     * @throws IllegalArgumentException if equity &lt;= 0, lotSize &lt;= 0, or entry equals stop
     */
    public static double computeQty(double equity, double riskPercent,
                                    double entryPrice, double stopPrice, double lotSize) {
        if (equity <= 0) {
            throw new IllegalArgumentException("equity must be > 0, got " + equity);
        }
        if (lotSize <= 0) {
            throw new IllegalArgumentException("lotSize must be > 0, got " + lotSize);
        }
        double distance = Math.abs(entryPrice - stopPrice);
        if (distance == 0) {
            throw new IllegalArgumentException(
                    "entry and stop prices are equal — cannot size position (entry=" + entryPrice + ")");
        }
        double riskAmount = equity * riskPercent / 100.0;
        double rawQty = riskAmount / distance;
        return Math.floor(rawQty / lotSize) * lotSize;
    }
}
