---
name: Bybit private WS drops every ~62s from missing ping/keep-alive
description: BybitV5WsClient connects successfully but Bybit idle-times out every ~62s (status 1006) because no ping frame is sent. Auto-reconnect works but creates 2s windows where position/execution/wallet frames are lost.
type: project
---

# Bybit private WS drops every ~62s from missing ping/keep-alive

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5WsClient.java` (Listener inner class) |
| Found during | Verifying post-deploy Docker container health on 2026-04-21 |
| Date | 2026-04-21 |

## Issue

`BybitV5WsClient.Listener` has `onOpen`, `onText`, `onClose`, but no periodic ping scheduler. Bybit V5 private WebSocket requires clients to send `{"op":"ping"}` every ~20s; the server closes idle connections after ~30s without a ping.

Observed log pattern:
```
17:14:45 WARN  Bybit WS closed for account 284 — status=1006 reason=
17:14:47 INFO  Bybit WS connected for account 284 (DEMO)
17:15:48 WARN  Bybit WS closed for account 284 — status=1006 reason=
17:15:50 INFO  Bybit WS connected for account 284 (DEMO)
...
```

Close every ~62s, reconnect in ~2s. Auto-reconnect via `SCHEDULER.schedule(connect, 2s)` works correctly — but during the 2-second gap, any position/execution/wallet frame published by Bybit is lost.

## Risks

1. **Missed fill events.** If a position fills or hits TP/SL during the 2s disconnect window, the frontend doesn't learn about it until the reconnect refetches state (and it only refetches via REST polling at 30s cadence — so up to a 32s UI lag).
2. **Database drift.** `handlePosition` / `handleExecution` update `executed_trades` from WS frames. A missed `position.size=0` frame means a closed position stays visible as OPEN until the next order reconciler run.
3. **Network noise.** Constant reconnect cycle churns DNS, HTTPS handshake, auth HMAC — small CPU waste and log noise.

## Suggested Solutions

1. **Add a ping scheduler to Listener.** In `onOpen`, schedule a task that sends `{"op":"ping","req_id":"..."}` every 20s until `onClose` fires. Cancel the task in `onClose`. One extra daemon scheduler thread per account, bounded.

```java
private ScheduledFuture<?> pingTask;

@Override
public void onOpen(WebSocket ws) {
    // ... existing auth + subscribe ...
    pingTask = SCHEDULER.scheduleAtFixedRate(
        () -> ws.sendText("{\"op\":\"ping\"}", true),
        20, 20, TimeUnit.SECONDS);
}

@Override
public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
    if (pingTask != null) pingTask.cancel(false);
    // ... existing reconnect logic ...
}
```

2. **Use HttpClient's built-in WS ping.** JDK `WebSocket.sendPing(...)` is available but Bybit V5 expects an application-level ping (`{"op":"ping"}`), not a protocol-level ping frame. The app-level form is what this codebase should send.

3. **Handle pong responses.** Server responds `{"op":"pong","success":true}`. Not strictly required to observe — the periodic ping alone keeps the connection alive — but a pong-timeout watchdog (close WS if no pong in 2 ping cycles) would detect half-open connections earlier than the 62s default.
