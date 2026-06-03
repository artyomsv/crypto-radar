# Signal Config & Backtest API Contract

Internal contract for backend Agent A (config), Agent B (backtest), and frontend Agents D/E.
Agents must NOT diverge from these shapes without coordination.

## Base URLs

- Signal-service direct: `http://signal-service:8086/api/signals/...`
- Through gateway: `http://api-gateway:8080/api/signals/...`

Frontend uses gateway. Backend services use signal-service directly.

---

## Config endpoints

### GET `/api/signals/config`
Returns current active config + version metadata.

**Response 200**
```json
{
  "id": 12,
  "version": 12,
  "config": { /* full SignalConfig */ },
  "description": "tightened RSI thresholds for choppy market",
  "parentVersionId": 10,
  "isActive": true,
  "createdAt": "2026-04-30T22:00:00Z",
  "createdBy": "system"
}
```

### GET `/api/signals/config/versions`
Lists versions, newest first.

**Query params**
- `limit` (int, default 50, max 200)
- `offset` (int, default 0)

**Response 200**
```json
[ /* SignalConfigVersion[] */ ]
```

### GET `/api/signals/config/versions/{id}`
**Response 200** — single SignalConfigVersion. **404** if missing.

### POST `/api/signals/config/versions`
Creates new immutable version. NOT activated. Auto-triggers Tier 1 backtest against last 30 days against the new version.

**Request body**
```json
{
  "config": { /* full SignalConfig */ },
  "description": "tighten alignment threshold",
  "parentVersionId": 12
}
```

**Validation**
- `config.weights.sum()` must equal 1.0 (±0.001 tolerance)
- All thresholds within `rsi`, `bollinger`, `derivativesFunding`, `longShortRatio`, `fearGreed` must be monotonic
- Score values must be in [-100, 100]

**Response 201** — created SignalConfigVersion (with `id` populated, `isActive: false`).
**Response 400** — `{ "error": "specific message" }` on validation failure.

### POST `/api/signals/config/versions/{id}/activate`
Atomically swaps the active version. Engine reloads on next 30s tick.

**Response 200** — newly active SignalConfigVersion.
**Response 404** — version doesn't exist.

---

## Backtest endpoints

### POST `/api/signals/backtest`
Runs a backtest synchronously.

**Request body**
```json
{
  "configVersionId": 13,
  "periodStart": "2026-04-01T00:00:00Z",
  "periodEnd": "2026-04-30T00:00:00Z",
  "tier": 1
}
```

**Response 200** — created BacktestRun (without trades).
**Response 400** — invalid period or tier.

### GET `/api/signals/backtest/runs`
Lists runs. Used by config page to show "latest backtest result for each version".

**Query params**
- `configVersionId` (int, optional)
- `limit` (int, default 50)

**Response 200** — `BacktestRun[]`.

### GET `/api/signals/backtest/runs/{id}`
**Response 200** — `BacktestRunDetail` (run + per-trade rows).
**Response 404** — run doesn't exist.

---

## Gateway proxy entries

All routes above must be proxied through `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java`. Pattern:

```java
@GET
@Path("/signals/config")
public Response getActiveConfig() {
    return proxyResponse(serviceClient.getRaw(
        serviceClient.getSignalServiceUrl() + "/api/signals/config"));
}
```

For POST endpoints use `serviceClient.postRaw(url, body)` and forward request body.

---

## Frontend API client methods (`frontend/src/lib/api.ts`)

```typescript
getSignalConfig: () => fetchJson<SignalConfigVersion>('/api/signals/config'),

listSignalConfigVersions: (limit = 50, offset = 0) =>
  fetchJson<SignalConfigVersion[]>(`/api/signals/config/versions?limit=${limit}&offset=${offset}`),

getSignalConfigVersion: (id: number) =>
  fetchJson<SignalConfigVersion>(`/api/signals/config/versions/${id}`),

saveSignalConfigVersion: async (config: SignalConfig, description: string, parentVersionId?: number) => {
  const res = await fetch(`${API_BASE}/api/signals/config/versions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ config, description, parentVersionId }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` }));
    throw new Error(err.error ?? `HTTP ${res.status}`);
  }
  return await res.json() as SignalConfigVersion;
},

activateSignalConfigVersion: async (id: number) => {
  const res = await fetch(`${API_BASE}/api/signals/config/versions/${id}/activate`, { method: 'POST' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json() as SignalConfigVersion;
},

runBacktest: async (configVersionId: number, periodStart: string, periodEnd: string, tier = 1) => {
  const res = await fetch(`${API_BASE}/api/signals/backtest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ configVersionId, periodStart, periodEnd, tier }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json() as BacktestRun;
},

listBacktestRuns: (configVersionId?: number, limit = 50) => {
  const params = new URLSearchParams({ limit: String(limit) });
  if (configVersionId) params.set('configVersionId', String(configVersionId));
  return fetchJson<BacktestRun[]>(`/api/signals/backtest/runs?${params}`);
},

getBacktestRun: (id: number) =>
  fetchJson<BacktestRunDetail>(`/api/signals/backtest/runs/${id}`),
```

---

## Hot-reload contract for SignalEngine

`SignalEngine` reads `configService.getActive()` once per `computeSignal` call, returning an immutable `SignalConfig` snapshot. `ConfigService` runs an `@Scheduled(every="30s")` job that:
1. Queries `SELECT id, config FROM signal_config_versions WHERE is_active = true LIMIT 1`
2. If id changed since last load, atomic-swap the `AtomicReference<SignalConfig>`
3. Logs at INFO level with structured `MDC` (`{"event": "config_reload", "fromVersion": X, "toVersion": Y}`)

Engine MUST NOT call DB on each `computeSignal` — only the cached AtomicReference.
