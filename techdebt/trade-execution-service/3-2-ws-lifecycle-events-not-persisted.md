# Bybit WS lifecycle events (WS_DISCONNECTED / WS_RECONNECTED) not persisted

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5WsClient.java` — `Listener.onClose` + `Listener.onOpen` |
| Found during | Plan 2b Task 9 code-quality review |
| Date | 2026-04-21 |

## Issue

`ExecutionEventType` defines `WS_CONNECTED` / `WS_RECONNECTED` / `WS_DISCONNECTED` specifically to give operators a lifecycle audit trail for the Bybit private WS connection. The current `BybitV5WsClient` implementation never persists any of these events.

The spec (plan 2b Task 9) showed persisting `WS_RECONNECTED` in `onOpen` and `WS_DISCONNECTED` in `onClose` from inside the `Listener` inner class. That doesn't work: the Java `HttpClient` WS callback thread has no CDI request context or active JTA transaction, so a naked `eventRepo.persist(...)` inside the callback would either NPE, silently no-op, or throw `TransactionRequiredException`. The implementer correctly left this as a log-only path rather than shipping broken code.

Downstream effect: during rolling disconnects, ops will see a gap in `ORDER_FILLED` / `POSITION_CLOSED` events for ~30s, with no corresponding lifecycle events explaining why. Diagnosis will rely on grepping container logs, which is slower + more brittle than a DB audit trail.

## Risks

- **Audit-trail gap.** No DB record of when WS auth failed, when reconnect was triggered, when a specific account's stream went dark. Every investigation starts with "check logs."
- **Monitoring blind spot.** Alerts can't be wired to "no `WS_CONNECTED` event in the last 5 minutes for account X" — because those events never fire.
- **Reconciler compensation is silent.** When `OrderReconciler` closes a row as "CLOSED externally" after a long WS outage, there's no lifecycle event explaining that the WS was down at that time.

## Suggested Solutions

### Option 1 — Dispatch via a `@Transactional` method on the outer bean (preferred)

`BybitV5WsClient.handleMessage(...)` is already a `@Transactional` public method that gets proxied. Add:

```java
@Transactional
public void persistLifecycleEvent(Long accountId, ExecutionEventType type, Map<String, Object> metadata) {
    ExecutionEvent ev = new ExecutionEvent();
    ev.setExchangeAccountId(accountId);
    ev.setEventType(type);
    ev.setMetadata(metadata);
    eventRepo.persist(ev);
}
```

And call it from `Listener.onOpen` / `Listener.onClose`:

```java
@Override
public void onClose(WebSocket ws, int statusCode, String reason) {
    LOG.warnf(...);
    try {
        BybitV5WsClient.this.persistLifecycleEvent(
                account.getId(), ExecutionEventType.WS_DISCONNECTED,
                Map.of("status", statusCode, "reason", reason == null ? "" : reason));
    } catch (RuntimeException e) {
        LOG.warnf(e, "persist WS_DISCONNECTED failed for account %d", account.getId());
    }
    SCHEDULER.schedule(() -> connect(account), 2, TimeUnit.SECONDS);
    return null;
}
```

Pro: reuses existing Quarkus tx infrastructure; CDI proxies intercept on `BybitV5WsClient.this.persistLifecycleEvent(...)` calls.
Con: need to verify CDI request context activates for a self-invocation via `this.` through a proxy. May need `@ActivateRequestContext` annotation on the persist method (harmless if unneeded).

### Option 2 — Dispatch via Quarkus `ManagedExecutor` or Mutiny Uni

Let the listener callback schedule the persist on a proper worker thread with CDI context. ~20 lines more code.

### Option 3 — Add a separate `@ApplicationScoped` helper

Split `WsLifecycleRecorder` as its own bean injected into `BybitV5WsClient` to make the transaction boundary unambiguous.

### Recommendation

**Option 1** — minimal change, reuses existing proxy, <30 LOC. Add a regression test that calls `persistLifecycleEvent` directly and asserts an `ExecutionEvent` row is persisted. Ship before real Bybit soak-testing begins so the operational dashboard has reliable data.
