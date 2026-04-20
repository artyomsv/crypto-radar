# OrderPlacer leaves orphan PENDING_PLACE row when Bybit REST throws

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderPlacer.java` `place()` method |
| Found during | Plan 2b Task 4 spec review |
| Date | 2026-04-20 |

## Issue

`OrderPlacer.place()` persists a `PENDING_PLACE` row BEFORE calling `bybit.placeOrder()`:

```java
// line ~93 (approximate)
tradeRepo.persist(trade);                                      // PENDING_PLACE row committed
trade.setExchangeOrderLinkId("ex-" + trade.getId());

// ... build orderReq ...

BybitResponse<PlaceOrderResult> resp;
try {
    resp = bybit.placeOrder(...);
} catch (RuntimeException e) {
    LOG.errorf(e, "placeOrder threw for %s/%s", ...);
    return fail(account, req, "Bybit call exception: " + e.getMessage());
}
```

`fail(...)` then persists a SECOND `ExecutedTrade` row with `status=FAILED`:

```java
private ExecutedTrade fail(ExchangeAccount account, PlacementRequest req, String reason) {
    ExecutedTrade t = new ExecutedTrade();
    // ... copies fields from req, NOT from the already-persisted `trade` ...
    t.setStatus(TradeStatus.FAILED);
    tradeRepo.persist(t);       // second row persisted
    logEvent(account, t, ExecutionEventType.ORDER_REJECTED, Map.of("reason", reason));
    return t;
}
```

Because `place()` is `@Transactional` and `fail()` returns normally (does not rethrow), Hibernate commits BOTH rows when the outer transaction completes. Result per failed-RPC signal:

- 1 row with `status = PENDING_PLACE` (no Bybit order ever exists for it)
- 1 row with `status = FAILED`

## Risks

- **Polluted ledger.** Every connectivity blip between the service and Bybit leaves an orphan `PENDING_PLACE` row. `OrderReconciler` (Plan 2b Task 6) walks `PENDING_PLACE` rows — it will see these orphans and either try to reconcile them against a non-existent Bybit order (waste) or flag them as stuck (false alarm).
- **Dedup false positive.** Plan 2b Task 7 `SignalSubscriber` uses `findOpenBySymbolAndDirectionAndStrategy` to dedup incoming signals. A stuck `PENDING_PLACE` row will block every subsequent signal for the same symbol/direction/strategy triple until manually cleaned up.
- **Misleading metrics.** Any `COUNT(*) WHERE status = 'PENDING_PLACE'` query (e.g., a future TradingResource metric) misrepresents the queue depth.

None of the 4 `OrderPlacerTest` cases exercise the `RuntimeException` path from `placeOrder` — all 4 use WireMock stubs that return a retCode response body. So the bug is silent in CI.

## Suggested Solutions

### Option 1 — Mutate the existing trade row on throw (preferred)

Replace the catch block's `fail()` call with in-place mutation of the already-persisted `trade`:

```java
} catch (RuntimeException e) {
    LOG.error("placeOrder threw for " + req.symbol() + "/" + req.direction(), e);
    trade.setStatus(TradeStatus.FAILED);
    logEvent(account, trade, ExecutionEventType.ORDER_REJECTED,
            Map.of("reason", "Bybit call exception"));
    return trade;
}
```

Remove the now-only-caller-is-itself `fail(...)` method (or keep only for the `qty <= 0` early-return path — in which case `fail()` is the ONLY place that validly creates a brand-new FAILED row without a prior `persist`).

Pro: single row per signal, natural Hibernate dirty-check flush, no orphans.
Con: if `qty <= 0` we still need `fail()` to create a row — acceptable, keep it for that path only.

### Option 2 — Throw to rollback

Rethrow the exception from the catch block to trigger Hibernate rollback:

```java
} catch (RuntimeException e) {
    LOG.errorf(e, "placeOrder threw ...");
    throw new OrderPlacementException(e);
}
```

Pro: no row persisted on throw — cleanest state.
Con: caller (`SignalSubscriber`) must handle the exception gracefully; may rollback too much if the Redis consumer has other side-effects in the same transaction.

### Option 3 — Add a regression test

Before any code fix, add:

```java
@Test
void bybitRestExceptionMutatesExistingRowNotsOrphan() {
    stubFor(post(urlPathEqualTo("/v5/order/create"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(...);
    placer.place(account, req);
    assertEquals(1, tradeRepo.count());  // one row, not two
    assertEquals(TradeStatus.FAILED, tradeRepo.listAll().get(0).getStatus());
}
```

### Recommendation

Ship **Option 1** (minimal diff, preserves idempotent behavior, doesn't change caller contract) + add the Option 3 test as the regression guard. Likely 30 minutes of work; do it before `OrderReconciler` (Task 6) lands so reconciler never sees the orphan rows in the wild.
