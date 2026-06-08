package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RUnitMathTest {

    @Test
    void qtyForOnePercentRiskOnFiftyBpsStop() {
        // equity=1000, risk=1% → $10 risk
        // entry=50000, stop=49750 → distance=250
        // rawQty = 10/250 = 0.04
        // lotSize=0.001 → floor(0.04 / 0.001) * 0.001 = 0.040
        double qty = RUnitMath.computeQty(1000.0, 1.0, 50000.0, 49750.0, 0.001);
        assertEquals(0.040, qty, 1e-9);
    }

    @Test
    void qtyRoundsDownToLotSize() {
        // equity=1000, risk=1% → $10 risk
        // entry=50000, stop=49000 → distance=1000
        // rawQty = 10/1000 = 0.01
        // lotSize=0.003 → floor(0.01 / 0.003) * 0.003 = 3 * 0.003 = 0.009
        double qty = RUnitMath.computeQty(1000.0, 1.0, 50000.0, 49000.0, 0.003);
        assertEquals(0.009, qty, 1e-9);
    }

    @Test
    void qtyWorksForShort() {
        // Distance is absolute — direction doesn't matter
        // equity=1000, risk=1%, entry=3000, stop=3050 → distance=50
        // rawQty = 10/50 = 0.2, lotSize=0.01 → 0.20
        double qty = RUnitMath.computeQty(1000.0, 1.0, 3000.0, 3050.0, 0.01);
        assertEquals(0.20, qty, 1e-9);
    }

    @Test
    void qtyScalesWithEquity() {
        double qty1k = RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.01);
        double qty10k = RUnitMath.computeQty(10000.0, 1.0, 100.0, 99.0, 0.01);
        assertEquals(qty1k * 10.0, qty10k, 1e-9);
    }

    @Test
    void qtyScalesWithRiskPercent() {
        double qty1pct = RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.01);
        double qty2pct = RUnitMath.computeQty(1000.0, 2.0, 100.0, 99.0, 0.01);
        assertEquals(qty1pct * 2.0, qty2pct, 1e-9);
    }

    @Test
    void zeroStopDistanceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(1000.0, 1.0, 100.0, 100.0, 0.01));
    }

    @Test
    void zeroOrNegativeEquityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(0.0, 1.0, 100.0, 99.0, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(-100.0, 1.0, 100.0, 99.0, 0.01));
    }

    @Test
    void zeroOrNegativeLotSizeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.0));
    }

    @Test
    void zeroOrNegativeRiskPercentRejected() {
        // Guard prevents silent qty=0 (was previously a silent zero) or
        // negative qty if a misconfigured riskPercent slipped through.
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(1000.0, 0.0, 100.0, 99.0, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(1000.0, -1.0, 100.0, 99.0, 0.01));
    }

    @Test
    void riskPercentAsFraction() {
        // riskPercent is whole-number percent (1.0 = 1%), not a fraction (0.01 = 1%)
        // Confirm 1.0 argument means 1%: equity=1000, rp=1.0 → risk=10
        double qty = RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.01);
        // distance=1, rawQty=10, floor(10/0.01)*0.01 = 10.00
        assertEquals(10.0, qty, 1e-9);
    }
}
