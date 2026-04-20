# RUnitMath.computeQty does not validate riskPercent

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Trivial |
| Location | `shared-trade-core/src/main/java/com/cryptoradar/core/RUnitMath.java` |
| Found during | Plan 1 final code review (2026-04-20) |
| Date | 2026-04-20 |

## Issue

`RUnitMath.computeQty(double equity, double riskPercent, double entryPrice, double stopPrice, double lotSize)` validates `equity > 0`, `lotSize > 0`, and `entry != stop`, but does NOT validate `riskPercent`.

Passing `riskPercent=0` silently returns `qty=0.0`. Passing `riskPercent=-1.0` returns a negative quantity — mathematically nonsensical for position sizing. Callers are expected to pass a sensible whole-number percent (1.0 = 1%), but the utility does not enforce that contract.

## Risks

- The upcoming `trade-execution-service` (Plan 2) will consume `RUnitMath` to size real orders on Bybit. If a settings bug or UI edge case lets `riskPercent=0` slip through, the service would compute `qty=0`, fail order placement with an opaque error, and log a failure that doesn't point at the root cause.
- A negative `riskPercent` would produce a negative `qty`, which may crash or produce undefined behavior deeper in the order pipeline depending on how the Bybit client serializes it.

## Suggested Solutions

Add a guard at the top of `computeQty` alongside the existing validations:

```java
if (riskPercent <= 0) {
    throw new IllegalArgumentException("riskPercent must be > 0, got " + riskPercent);
}
```

Plus a test case in `RUnitMathTest.java`:

```java
@Test
void zeroOrNegativeRiskPercentRejected() {
    assertThrows(IllegalArgumentException.class,
            () -> RUnitMath.computeQty(1000.0, 0.0, 100.0, 99.0, 0.01));
    assertThrows(IllegalArgumentException.class,
            () -> RUnitMath.computeQty(1000.0, -1.0, 100.0, 99.0, 0.01));
}
```

Trivial to implement. Defer until Plan 2 starts so it lands in the same review batch as the trade-execution-service integration.
