# Plan 3 — Portfolio UI (Bybit Exchange Section)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Portfolio page with a per-exchange section that mirrors the `trade-execution-service` state (Bybit demo/mainnet) — account setup, live equity/positions, safety controls (kill switch, auto-trade toggle, first-time confirmation), close-one / close-all actions, settings panel, and WebSocket-backed live updates with REST polling fallback.

**Architecture:** React 19 + TypeScript 5.7 + Vite 6 + Tailwind 3.4 extending the existing `frontend/` app. New `ExchangeAccountsSection` lives below the existing `PortfolioTracker` in the Portfolio page. All backend calls go through the api-gateway at `http://localhost:31080/api/execution/*` (proxied by `ProxyResource` — shipped in Plan 2b Task 10). WebSocket connects to `ws://localhost:31080/ws/execution` (proxied by `ExecutionWebSocketProxy`). `useExecutionStream` hook unifies WS + 15s REST polling fallback and exposes a single `{ wallet, positions, trades, events, connectionState, secondsSinceUpdate }` shape to all consumers.

**Tech Stack:** React 19, TypeScript 5.7, Vite 6, Tailwind 3.4, `lucide-react` icons, `@radix-ui/react-*` primitives (already in `package.json` — reuse), `class-variance-authority` + `clsx` + `tailwind-merge` for className composition (existing patterns).

**Spec reference:** `docs/superpowers/specs/2026-04-20-trade-execution-service-design.md` — Section 5 ("Frontend contract") + "Frontend visual decisions (locked 2026-04-21)" addendum.

**Verification style:** Frontend has no unit-test infrastructure (no vitest, no testing-library). Per `reference-pattern-adoption.md`, each task verifies through the running dev server + browser DevTools + curl against the api-gateway, NOT `npm test`. Adding a test harness is out of scope for this plan — file as tech-debt if desired.

---

## Prerequisites

- trade-execution-service running at `localhost:31087`, api-gateway at `localhost:31080`.
- A Bybit demo API key already minted (Derivatives: Order + Position; no Withdraw).
- `EXECUTION_MASTER_KEY` set in `.env` (32-byte AES-256 base64).
- `EXECUTION_DEV_MODE_ENABLED=true` for smoke-testing via `/api/execution/test/inject-signal`.

Start stack if not already up:
```bash
docker compose up -d --build trade-execution-service api-gateway frontend
```

Run dev server with hot reload during Plan 3 implementation:
```bash
cd frontend && npm install && npm run dev
```
Dev server defaults to port 5173 but Vite config may proxy `/api` → `api-gateway:8080`. Verify by:
```bash
curl -fsS http://localhost:5173/api/execution/accounts
```
Expect `[]` when no accounts registered.

---

## File structure overview

New under `frontend/src/components/portfolio/`:

```
ExchangeAccountsSection.tsx      NEW — list of exchange cards + empty state
ExchangeCard.tsx                  NEW — per-account shell (header + equity + positions + recent)
ExchangeCardHeader.tsx            NEW — logo / status dot / toggles / kill-switch / settings
EquitySummary.tsx                 NEW — 5-card strip (equity / avail / openPnl / today / positions)
OpenPositionsTable.tsx            NEW — rows + detector badges + row ⋯ menu
PositionRowMenu.tsx               NEW — popover: View chart / Why / Close at market
RecentTradesList.tsx              NEW — last 24h closed with TARGET/TRAIL/STOP/EXPIRED badges
WhyModal.tsx                      NEW — reuses AiAnalysisModal scaffold
ExchangeSetupModal.tsx            NEW — single-step form for adding a new account
AddExchangeButton.tsx             NEW — dashed-border CTA card when no account exists
FirstTimeAutoTradeModal.tsx       NEW — confirm-first-time per account (localStorage-gated)
SettingsPanel.tsx                 NEW — slide-in from right, 7 fields, PATCH on save
KillSwitchBanner.tsx              NEW — red banner shown when killSwitch=true
ConnectionIndicator.tsx           NEW — status dot + staleness hint + POLLING FALLBACK pill
```

New under `frontend/src/hooks/`:

```
useExecutionStream.ts             NEW — WS subscribe + REST polling fallback + staleness
useExecutionAccounts.ts           NEW — list + refresh + create + patch + delete
```

Modified:

```
frontend/src/types/index.ts                                 — add 7 execution types
frontend/src/lib/api.ts                                      — add api.execution.* wrappers
frontend/src/components/dashboard/PortfolioTracker.tsx       — no change; sits above ExchangeAccountsSection
frontend/src/App.tsx                                          — mount ExchangeAccountsSection below PortfolioTracker in the Portfolio route (exact wiring in Task 13)
CLAUDE.md                                                    — Plan 3 docs entry (Task 13)
```

---

## Task 1: TypeScript types + API wrappers

**Files:**
- Modify: `frontend/src/types/index.ts` — append 7 execution interfaces
- Modify: `frontend/src/lib/api.ts` — add `api.execution` namespace

- [ ] **Step 1: Append execution types to `frontend/src/types/index.ts`**

Append at the end of the file (do not touch existing exports):

```typescript
// ==========================================================================
// Trade execution service (Plan 2b / Plan 3)
// ==========================================================================

export interface ExchangeAccount {
    id: number;
    exchange: string;
    environment: 'DEMO' | 'MAINNET';
    label: string | null;
    keyMask: string;
    autoTradeEnabled: boolean;
    killSwitch: boolean;
    riskPercent: number;
    defaultLeverage: number;
    maxConcurrentPositions: number;
    maxDailyLossPercent: number;
    signalAgeSeconds: number;
    positionMaxAgeHours: number;
    flipPersistenceTicks: number;
    createdAt: string;
    updatedAt: string;
}

export interface WalletSnapshot {
    equity: number;
    available: number;
    openPnl: number;
    todayRealized: number;
    positionsOpen: number;
}

export interface ExecutionPosition {
    id: number;
    accountId: number;
    signalId: string | null;
    symbol: string;
    direction: 'LONG' | 'SHORT';
    strategy: string | null;
    status: 'PENDING_PLACE' | 'OPEN' | 'CLOSING' | 'CLOSED' | 'FAILED' | 'CANCELLED';
    entryPrice: number | null;
    qty: number | null;
    leverage: number | null;
    stopPrice: number;
    targetPrice: number;
    dynamicStopPrice: number | null;
    trailHighestR: number;
    trailTriggeredAt: string | null;
    openedAt: string;
}

export interface ExecutionTrade {
    id: number;
    signalId: string | null;
    symbol: string;
    direction: 'LONG' | 'SHORT';
    strategy: string | null;
    status: ExecutionPosition['status'];
    entryPrice: number | null;
    exitPrice: number | null;
    qty: number | null;
    realizedPnlUsdt: number | null;
    realizedRMultiple: number | null;
    feesUsdt: number | null;
    exitReason: string | null;
    openedAt: string;
    closedAt: string | null;
}

export interface ExecutionEvent {
    id: number;
    eventType: string;
    signalId: string | null;
    executedTradeId: number | null;
    metadata: Record<string, unknown>;
    createdAt: string;
}

export interface WhyView {
    tradeId: number;
    signalId: string | null;
    symbol: string;
    direction: string;
    strategy: string | null;
    openedAt: string;
    signalSnapshot: Record<string, unknown>;
}

export interface CreateAccountRequest {
    exchange: 'BYBIT';
    environment: 'DEMO' | 'MAINNET';
    apiKey: string;
    apiSecret: string;
    label?: string;
}

export interface UpdateAccountRequest {
    label?: string;
    autoTradeEnabled?: boolean;
    killSwitch?: boolean;
    riskPercent?: number;
    defaultLeverage?: number;
    maxConcurrentPositions?: number;
    maxDailyLossPercent?: number;
    signalAgeSeconds?: number;
    positionMaxAgeHours?: number;
    flipPersistenceTicks?: number;
}
```

- [ ] **Step 2: Append `api.execution.*` wrappers to `frontend/src/lib/api.ts`**

Add to the imports at the top:

```typescript
import type {
    ExchangeAccount,
    WalletSnapshot,
    ExecutionPosition,
    ExecutionTrade,
    ExecutionEvent,
    WhyView,
    CreateAccountRequest,
    UpdateAccountRequest,
} from '@/types';
```

Add a generic write helper (POST/PATCH/DELETE) — `fetchJson` in the existing file only handles GET. Place it right after `fetchJson`:

```typescript
async function sendJson<T>(url: string, method: 'POST' | 'PATCH' | 'DELETE', body?: unknown): Promise<{ data: T | null; error: string | null; status: number }> {
    try {
        const response = await fetch(`${API_BASE}${url}`, {
            method,
            headers: body ? { 'Content-Type': 'application/json' } : undefined,
            body: body ? JSON.stringify(body) : undefined,
        });
        const text = await response.text();
        const parsed = text ? JSON.parse(text) : null;
        if (!response.ok) {
            return {
                data: null,
                status: response.status,
                error: (parsed && typeof parsed === 'object' && 'error' in parsed) ? String(parsed.error) : `HTTP ${response.status}`,
            };
        }
        return { data: parsed as T, status: response.status, error: null };
    } catch (e) {
        return { data: null, status: 0, error: e instanceof Error ? e.message : 'network error' };
    }
}
```

Append to the existing `export const api = { ... }` object (add a comma after the last existing entry, then):

```typescript
    execution: {
        listAccounts: () => fetchJson<ExchangeAccount[]>('/api/execution/accounts'),
        getAccount: (id: number) => fetchJson<ExchangeAccount>(`/api/execution/accounts/${id}`),
        createAccount: (req: CreateAccountRequest) =>
            sendJson<ExchangeAccount>('/api/execution/accounts', 'POST', req),
        patchAccount: (id: number, req: UpdateAccountRequest) =>
            sendJson<ExchangeAccount>(`/api/execution/accounts/${id}`, 'PATCH', req),
        deleteAccount: (id: number) =>
            sendJson<null>(`/api/execution/accounts/${id}`, 'DELETE'),
        getWallet: (id: number) => fetchJson<WalletSnapshot>(`/api/execution/accounts/${id}/wallet`),
        getPositions: (id: number) => fetchJson<ExecutionPosition[]>(`/api/execution/accounts/${id}/positions`),
        getTrades: (id: number, limit = 50) =>
            fetchJson<ExecutionTrade[]>(`/api/execution/accounts/${id}/trades?limit=${limit}`),
        getEvents: (id: number, limit = 100) =>
            fetchJson<ExecutionEvent[]>(`/api/execution/accounts/${id}/events?limit=${limit}`),
        getWhy: (accountId: number, tradeId: number) =>
            fetchJson<WhyView>(`/api/execution/accounts/${accountId}/trades/${tradeId}/why`),
        toggleKillSwitch: (id: number, enabled: boolean) =>
            sendJson<{ killSwitch: boolean }>(`/api/execution/accounts/${id}/kill-switch`, 'POST', { enabled }),
        closeAll: (id: number, confirm: string) =>
            sendJson<{ closedCount: number }>(`/api/execution/accounts/${id}/close-all`, 'POST', { confirm }),
        closeTrade: (accountId: number, tradeId: number) =>
            sendJson<ExecutionTrade>(`/api/execution/accounts/${accountId}/trades/${tradeId}/close`, 'POST'),
    },
```

- [ ] **Step 3: Verify compile**

```bash
cd frontend && npm run build
```
Expected: `tsc -b && vite build` completes with no errors. Bundle created in `frontend/dist/`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/lib/api.ts
git commit -m "feat(frontend): execution types + api.execution wrappers"
```

---

## Task 2: `useExecutionStream` hook

**Files:**
- Create: `frontend/src/hooks/useExecutionStream.ts`

**Goal:** One hook returns live `{ wallet, positions, trades, events, connectionState, secondsSinceUpdate }` for a single account. Connects to `ws://<host>/ws/execution` for real-time updates. If WS closes, falls back to polling `/wallet` + `/positions` every 15s until WS returns. Staleness counter ticks every 1s.

- [ ] **Step 1: Create the hook**

Write `frontend/src/hooks/useExecutionStream.ts`:

```typescript
import { useEffect, useRef, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import type { WalletSnapshot, ExecutionPosition, ExecutionTrade, ExecutionEvent } from '@/types';

export type ConnectionState = 'connected' | 'reconnecting' | 'disconnected';

interface UseExecutionStreamResult {
    wallet: WalletSnapshot | null;
    positions: ExecutionPosition[];
    trades: ExecutionTrade[];
    events: ExecutionEvent[];
    connectionState: ConnectionState;
    secondsSinceUpdate: number;
    refresh: () => Promise<void>;
}

const POLL_INTERVAL_MS = 15_000;
const STALENESS_TICK_MS = 1_000;
const WS_RECONNECT_DELAY_MS = 5_000;

function wsUrlFor(): string {
    const loc = window.location;
    const protocol = loc.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${loc.host}/ws/execution`;
}

export function useExecutionStream(accountId: number | null): UseExecutionStreamResult {
    const [wallet, setWallet] = useState<WalletSnapshot | null>(null);
    const [positions, setPositions] = useState<ExecutionPosition[]>([]);
    const [trades, setTrades] = useState<ExecutionTrade[]>([]);
    const [events, setEvents] = useState<ExecutionEvent[]>([]);
    const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected');
    const [lastUpdateAt, setLastUpdateAt] = useState<number>(Date.now());
    const [secondsSinceUpdate, setSecondsSinceUpdate] = useState<number>(0);

    const wsRef = useRef<WebSocket | null>(null);
    const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

    const refresh = useCallback(async () => {
        if (accountId == null) return;
        const [w, p, t, e] = await Promise.all([
            api.execution.getWallet(accountId),
            api.execution.getPositions(accountId),
            api.execution.getTrades(accountId, 50),
            api.execution.getEvents(accountId, 100),
        ]);
        if (w) setWallet(w);
        if (p) setPositions(p);
        if (t) setTrades(t);
        if (e) setEvents(e);
        setLastUpdateAt(Date.now());
    }, [accountId]);

    // REST polling fallback — runs only while WS is not connected
    useEffect(() => {
        if (accountId == null) return;
        if (connectionState === 'connected') {
            if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null; }
            return;
        }
        // Kick an immediate refresh, then poll on interval
        refresh();
        pollTimerRef.current = setInterval(refresh, POLL_INTERVAL_MS);
        return () => {
            if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null; }
        };
    }, [accountId, connectionState, refresh]);

    // Staleness tick — updates secondsSinceUpdate every 1s
    useEffect(() => {
        const t = setInterval(() => {
            setSecondsSinceUpdate(Math.floor((Date.now() - lastUpdateAt) / 1000));
        }, STALENESS_TICK_MS);
        return () => clearInterval(t);
    }, [lastUpdateAt]);

    // WebSocket connection + reconnect loop
    useEffect(() => {
        if (accountId == null) return;
        let cancelled = false;
        let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

        const connect = () => {
            if (cancelled) return;
            setConnectionState(prev => prev === 'connected' ? prev : 'reconnecting');
            const ws = new WebSocket(wsUrlFor());
            wsRef.current = ws;
            ws.onopen = () => {
                if (cancelled) { ws.close(); return; }
                setConnectionState('connected');
                // Kick an initial REST refresh so numbers aren't stale on connect
                refresh();
            };
            ws.onmessage = (ev) => {
                try {
                    const msg = JSON.parse(ev.data);
                    // Bybit WS topic envelope — we pass-through everything relevant
                    // to this account id. Concrete filtering is deferred to downstream
                    // components; this hook simply refreshes on any message so
                    // numbers stay current without having to parse every topic shape.
                    if (msg && typeof msg === 'object') {
                        refresh();
                    }
                } catch { /* ignore parse errors — server pushes raw Bybit JSON */ }
            };
            ws.onclose = () => {
                if (cancelled) return;
                setConnectionState('reconnecting');
                reconnectTimer = setTimeout(connect, WS_RECONNECT_DELAY_MS);
            };
            ws.onerror = () => { /* onclose will fire next */ };
        };

        connect();

        return () => {
            cancelled = true;
            if (reconnectTimer) clearTimeout(reconnectTimer);
            if (wsRef.current) {
                wsRef.current.onclose = null;
                wsRef.current.close();
                wsRef.current = null;
            }
            setConnectionState('disconnected');
        };
    }, [accountId, refresh]);

    return { wallet, positions, trades, events, connectionState, secondsSinceUpdate, refresh };
}
```

- [ ] **Step 2: Verify compile**

```bash
cd frontend && npm run build
```
Expected: clean build.

- [ ] **Step 3: Smoke verify (manual)**

Mount a throwaway debug panel in `PortfolioTracker.tsx` (do NOT commit this):
```tsx
// TEMPORARY — remove before Task 2 commit
import { useExecutionStream } from '@/hooks/useExecutionStream';
const stream = useExecutionStream(1);
console.log('stream', stream);
```

Register an account via curl (using your real demo key) so accountId=1 exists:
```bash
curl -X POST http://localhost:31080/api/execution/accounts \
  -H 'Content-Type: application/json' \
  -d '{"exchange":"BYBIT","environment":"DEMO","apiKey":"<key>","apiSecret":"<secret>","label":"smoke"}'
```

Open the dev server, check DevTools Console:
- Initial render: `connectionState: 'reconnecting'`, then `'connected'` within a few seconds.
- Network tab: shows WS handshake to `/ws/execution` and 4 parallel GETs on mount.
- Stop `api-gateway` container (`docker compose stop api-gateway`) → connectionState flips to `'reconnecting'`, polling GETs continue every 15s. Restart api-gateway → WS reconnects within 5s.

Remove the debug panel before committing.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/hooks/useExecutionStream.ts
git commit -m "feat(frontend): useExecutionStream hook with WS + polling fallback"
```

---

## Task 3: `ExchangeAccountsSection` + empty state + setup modal

**Files:**
- Create: `frontend/src/hooks/useExecutionAccounts.ts`
- Create: `frontend/src/components/portfolio/ExchangeAccountsSection.tsx`
- Create: `frontend/src/components/portfolio/AddExchangeButton.tsx`
- Create: `frontend/src/components/portfolio/ExchangeSetupModal.tsx`

- [ ] **Step 1: `useExecutionAccounts` hook**

Write `frontend/src/hooks/useExecutionAccounts.ts`:

```typescript
import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { ExchangeAccount, CreateAccountRequest, UpdateAccountRequest } from '@/types';

export function useExecutionAccounts() {
    const [accounts, setAccounts] = useState<ExchangeAccount[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        setLoading(true);
        const data = await api.execution.listAccounts();
        if (data) setAccounts(data);
        setLoading(false);
    }, []);

    const create = useCallback(async (req: CreateAccountRequest) => {
        const { data, error, status } = await api.execution.createAccount(req);
        if (data) {
            setAccounts(prev => [...prev, data]);
            return { success: true as const, account: data };
        }
        return { success: false as const, error: error ?? `HTTP ${status}`, status };
    }, []);

    const patch = useCallback(async (id: number, req: UpdateAccountRequest) => {
        const { data, error, status } = await api.execution.patchAccount(id, req);
        if (data) {
            setAccounts(prev => prev.map(a => a.id === id ? data : a));
            return { success: true as const, account: data };
        }
        return { success: false as const, error: error ?? `HTTP ${status}`, status };
    }, []);

    const remove = useCallback(async (id: number) => {
        const { error, status } = await api.execution.deleteAccount(id);
        if (error) return { success: false as const, error, status };
        setAccounts(prev => prev.filter(a => a.id !== id));
        return { success: true as const };
    }, []);

    useEffect(() => { refresh(); }, [refresh]);

    return { accounts, loading, error, refresh, create, patch, remove };
}
```

- [ ] **Step 2: `ExchangeSetupModal` component**

Write `frontend/src/components/portfolio/ExchangeSetupModal.tsx`:

```tsx
import { useState } from 'react';
import { X } from 'lucide-react';
import type { CreateAccountRequest } from '@/types';

interface Props {
    onClose: () => void;
    onSubmit: (req: CreateAccountRequest) => Promise<{ success: true } | { success: false; error: string; status: number }>;
    mainnetEnabled: boolean;
}

export function ExchangeSetupModal({ onClose, onSubmit, mainnetEnabled }: Props) {
    const [environment, setEnvironment] = useState<'DEMO' | 'MAINNET'>('DEMO');
    const [label, setLabel] = useState('');
    const [apiKey, setApiKey] = useState('');
    const [apiSecret, setApiSecret] = useState('');
    const [showSecret, setShowSecret] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSubmit = async () => {
        setError(null);
        if (!apiKey.trim() || !apiSecret.trim()) {
            setError('API key and secret are required.');
            return;
        }
        setSubmitting(true);
        const result = await onSubmit({
            exchange: 'BYBIT',
            environment,
            apiKey: apiKey.trim(),
            apiSecret: apiSecret.trim(),
            label: label.trim() || undefined,
        });
        setSubmitting(false);
        if (!result.success) {
            setError(result.error);
            return;
        }
        onClose();
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={onClose}>
            <div className="w-[420px] rounded-lg border border-[#1c1f27] bg-[#141820] p-5" onClick={e => e.stopPropagation()}>
                <div className="mb-4 flex items-center justify-between">
                    <div className="text-sm font-semibold text-white">Add Bybit account</div>
                    <button onClick={onClose} className="text-gray-400 hover:text-white"><X size={16} /></button>
                </div>

                {!mainnetEnabled && (
                    <div className="mb-3 rounded border border-[#f7a600] bg-[#2d1a0e] p-2 text-[10px] text-[#f7a600]">
                        ⚠ DEMO only until server-side mainnet flag is set
                    </div>
                )}

                <div className="mb-3">
                    <div className="mb-1 text-[10px] uppercase text-gray-400">Environment</div>
                    <div className="flex gap-1.5">
                        <button
                            type="button"
                            onClick={() => setEnvironment('DEMO')}
                            className={`flex-1 rounded px-2 py-2 text-xs font-semibold ${environment === 'DEMO' ? 'bg-[#1a73e8] text-white' : 'bg-[#222] text-gray-400'}`}>
                            DEMO
                        </button>
                        <button
                            type="button"
                            disabled={!mainnetEnabled}
                            onClick={() => mainnetEnabled && setEnvironment('MAINNET')}
                            className={`flex-1 rounded px-2 py-2 text-xs ${environment === 'MAINNET' ? 'bg-[#1a73e8] text-white font-semibold' : 'bg-[#222] text-gray-500'} ${!mainnetEnabled ? 'cursor-not-allowed' : ''}`}>
                            MAINNET {!mainnetEnabled && '(locked)'}
                        </button>
                    </div>
                </div>

                <div className="mb-3">
                    <label className="mb-1 block text-[10px] uppercase text-gray-400">Label (optional)</label>
                    <input
                        value={label}
                        onChange={e => setLabel(e.target.value)}
                        placeholder="My demo account"
                        className="w-full rounded border border-[#222] bg-[#0f1116] px-2 py-2 text-xs text-white placeholder-gray-600"
                    />
                </div>

                <div className="mb-3">
                    <label className="mb-1 block text-[10px] uppercase text-gray-400">API key</label>
                    <input
                        value={apiKey}
                        onChange={e => setApiKey(e.target.value)}
                        placeholder="XR7Dg8..."
                        className="w-full rounded border border-[#222] bg-[#0f1116] px-2 py-2 text-xs text-white placeholder-gray-600"
                    />
                </div>

                <div className="mb-3">
                    <label className="mb-1 block text-[10px] uppercase text-gray-400">API secret</label>
                    <div className="relative">
                        <input
                            type={showSecret ? 'text' : 'password'}
                            value={apiSecret}
                            onChange={e => setApiSecret(e.target.value)}
                            placeholder="••••••••••••"
                            className="w-full rounded border border-[#222] bg-[#0f1116] px-2 py-2 pr-12 text-xs text-white placeholder-gray-600"
                        />
                        <button
                            type="button"
                            onClick={() => setShowSecret(s => !s)}
                            className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-gray-400 hover:text-white">
                            {showSecret ? 'hide' : 'show'}
                        </button>
                    </div>
                </div>

                <div className="mb-4 rounded border-l-2 border-[#1a73e8] bg-[#141820] p-2 text-[10px] leading-relaxed text-gray-400">
                    On Bybit: <span className="text-gray-200 font-semibold">Derivatives → Order + Position</span>. NOT Withdraw. We reject withdraw-enabled keys.
                </div>

                {error && (
                    <div className="mb-3 rounded border border-[#ef4444] bg-[#2d0e0e] p-2 text-[11px] text-[#ef4444]">
                        {error}
                    </div>
                )}

                <div className="flex gap-2">
                    <button onClick={onClose} className="flex-1 rounded bg-[#222] px-2 py-2 text-xs text-gray-300 hover:bg-[#2a2f38]">Cancel</button>
                    <button
                        onClick={handleSubmit}
                        disabled={submitting}
                        className="flex-1 rounded bg-[#4ade80] px-2 py-2 text-xs font-semibold text-black hover:bg-[#6ee498] disabled:opacity-50">
                        {submitting ? 'Validating…' : 'Validate + save'}
                    </button>
                </div>
            </div>
        </div>
    );
}
```

- [ ] **Step 3: `AddExchangeButton` — dashed CTA card**

Write `frontend/src/components/portfolio/AddExchangeButton.tsx`:

```tsx
import { Plus } from 'lucide-react';

export function AddExchangeButton({ onClick }: { onClick: () => void }) {
    return (
        <div
            onClick={onClick}
            className="cursor-pointer rounded-lg border border-dashed border-[#333] bg-[#141820] p-8 text-center transition hover:border-[#1a73e8]">
            <div className="mx-auto mb-3 flex h-11 w-11 items-center justify-center rounded-full bg-[#1a73e8] text-white">
                <Plus size={22} />
            </div>
            <div className="mb-1 text-sm font-semibold text-white">Connect an exchange</div>
            <div className="mb-4 text-[10px] leading-relaxed text-gray-500">
                Mirror signal-service STRONG_BUY / STRONG_SELL<br />to real orders with native TP/SL + trailing stop.
            </div>
            <div className="inline-block rounded bg-[#1a73e8] px-4 py-1.5 text-xs font-semibold text-white">+ Add Bybit</div>
        </div>
    );
}
```

- [ ] **Step 4: `ExchangeAccountsSection` — scaffolding**

Write `frontend/src/components/portfolio/ExchangeAccountsSection.tsx`:

```tsx
import { useState } from 'react';
import { useExecutionAccounts } from '@/hooks/useExecutionAccounts';
import { AddExchangeButton } from './AddExchangeButton';
import { ExchangeSetupModal } from './ExchangeSetupModal';
import type { ExchangeAccount } from '@/types';

export function ExchangeAccountsSection() {
    const { accounts, loading, create } = useExecutionAccounts();
    const [showSetup, setShowSetup] = useState(false);

    if (loading) {
        return <div className="rounded-lg bg-[#141820] p-6 text-center text-xs text-gray-500">Loading exchanges…</div>;
    }

    return (
        <div className="flex flex-col gap-3">
            {accounts.length === 0 && <AddExchangeButton onClick={() => setShowSetup(true)} />}
            {accounts.map(account => (
                <ExchangeCardPlaceholder key={account.id} account={account} />
            ))}
            {accounts.length > 0 && (
                <button
                    onClick={() => setShowSetup(true)}
                    className="rounded-lg border border-dashed border-[#333] bg-[#141820] py-3 text-xs text-gray-500 hover:border-[#1a73e8] hover:text-gray-300">
                    + Add another exchange
                </button>
            )}
            {showSetup && (
                <ExchangeSetupModal
                    mainnetEnabled={false}
                    onClose={() => setShowSetup(false)}
                    onSubmit={create}
                />
            )}
        </div>
    );
}

function ExchangeCardPlaceholder({ account }: { account: ExchangeAccount }) {
    return (
        <div className="rounded-lg border border-[#1c1f27] bg-[#141820] p-4">
            <div className="text-sm font-semibold text-white">{account.exchange} ({account.environment})</div>
            <div className="text-[10px] text-gray-500">id={account.id} · kill={String(account.killSwitch)} · auto={String(account.autoTradeEnabled)}</div>
            <div className="mt-2 text-[10px] text-gray-500">(ExchangeCard renders here in Task 4)</div>
        </div>
    );
}
```

- [ ] **Step 5: Smoke verify**

Temporarily mount `ExchangeAccountsSection` inside `PortfolioTracker.tsx` at the bottom of its returned JSX (revert before Task 13 wires it into `App.tsx` properly):

```tsx
// At top of PortfolioTracker.tsx
import { ExchangeAccountsSection } from '@/components/portfolio/ExchangeAccountsSection';
```

```tsx
// At the bottom of PortfolioTracker's return, INSIDE the same wrapper element:
<div className="mt-4">
    <ExchangeAccountsSection />
</div>
```

Run `npm run dev`, open portfolio page:
- With zero accounts: sees dashed-CTA card below Manual section.
- Click `+ Add Bybit` → modal opens with the single-step form.
- Enter demo key+secret, click Validate + save → modal closes, placeholder card appears with `BYBIT (DEMO) id=N kill=true auto=false`.
- Click `+ Add another exchange` → modal opens again. Attempt duplicate → inline error `Account for BYBIT DEMO already exists`.

Revert the temporary edit to `PortfolioTracker.tsx` — Task 13 will do the proper wiring.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/hooks/useExecutionAccounts.ts \
        frontend/src/components/portfolio/ExchangeAccountsSection.tsx \
        frontend/src/components/portfolio/AddExchangeButton.tsx \
        frontend/src/components/portfolio/ExchangeSetupModal.tsx
git commit -m "feat(frontend): ExchangeAccountsSection + empty state + setup modal"
```

---

## Task 4: `ExchangeCard` shell + header + equity summary

**Files:**
- Create: `frontend/src/components/portfolio/ExchangeCard.tsx`
- Create: `frontend/src/components/portfolio/ExchangeCardHeader.tsx`
- Create: `frontend/src/components/portfolio/EquitySummary.tsx`
- Create: `frontend/src/components/portfolio/ConnectionIndicator.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeAccountsSection.tsx` — replace `ExchangeCardPlaceholder` with real `ExchangeCard`

- [ ] **Step 1: `ConnectionIndicator` (status dot + staleness)**

Write `frontend/src/components/portfolio/ConnectionIndicator.tsx`:

```tsx
import type { ConnectionState } from '@/hooks/useExecutionStream';

interface Props {
    state: ConnectionState;
    secondsSinceUpdate: number;
    environment: 'DEMO' | 'MAINNET';
}

export function ConnectionIndicator({ state, secondsSinceUpdate, environment }: Props) {
    const color = state === 'connected' ? '#4ade80' : state === 'reconnecting' ? '#f7a600' : '#ef4444';
    const label =
        state === 'connected' ? `Connected · ${environment} · v5` :
        state === 'reconnecting' ? `Reconnecting… (last update ${secondsSinceUpdate}s ago)` :
        `Disconnected (polling fallback)`;
    const pulse = state === 'reconnecting' ? 'animate-pulse' : '';
    return (
        <div className="text-[10px]" style={{ color }}>
            <span className={`mr-1 inline-block h-[6px] w-[6px] rounded-full ${pulse}`} style={{ backgroundColor: color }} />
            {label}
        </div>
    );
}
```

- [ ] **Step 2: `ExchangeCardHeader` (logo, name, status, toggles, kill, settings)**

Write `frontend/src/components/portfolio/ExchangeCardHeader.tsx`:

```tsx
import { Settings } from 'lucide-react';
import type { ExchangeAccount } from '@/types';
import { ConnectionIndicator } from './ConnectionIndicator';
import type { ConnectionState } from '@/hooks/useExecutionStream';

interface Props {
    account: ExchangeAccount;
    connectionState: ConnectionState;
    secondsSinceUpdate: number;
    onAutoTradeToggle: () => void;
    onKillSwitchClick: () => void;
    onSettingsClick: () => void;
}

export function ExchangeCardHeader({
    account, connectionState, secondsSinceUpdate,
    onAutoTradeToggle, onKillSwitchClick, onSettingsClick,
}: Props) {
    return (
        <div className="mb-4 flex items-center justify-between border-b border-[#1c1f27] pb-3">
            <div className="flex items-center gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded bg-[#f7a600] text-sm font-bold text-black">B</div>
                <div>
                    <div className="text-sm font-semibold text-white">Bybit</div>
                    <ConnectionIndicator
                        state={connectionState}
                        secondsSinceUpdate={secondsSinceUpdate}
                        environment={account.environment}
                    />
                </div>
            </div>
            <div className="flex items-center gap-2">
                <span className="text-[10px] text-gray-400">Auto-trade</span>
                <button
                    onClick={onAutoTradeToggle}
                    className={`relative h-[22px] w-10 rounded-full p-[2px] transition ${account.autoTradeEnabled ? 'bg-[#4ade80]' : 'bg-[#333]'}`}>
                    <div
                        className="h-[18px] w-[18px] rounded-full bg-white transition"
                        style={{ marginLeft: account.autoTradeEnabled ? 18 : 0 }}
                    />
                </button>
                <button
                    onClick={onKillSwitchClick}
                    className={`rounded px-3 py-1.5 text-[11px] font-semibold ${account.killSwitch ? 'bg-[#4ade80] text-black' : 'bg-[#ef4444] text-white'}`}>
                    {account.killSwitch ? 'DISARM' : 'KILL SWITCH'}
                </button>
                <button
                    onClick={onSettingsClick}
                    className="flex items-center gap-1 rounded border border-[#333] bg-[#222] px-2 py-1.5 text-[11px] text-gray-300 hover:bg-[#2a2f38]">
                    <Settings size={12} />
                </button>
            </div>
        </div>
    );
}
```

- [ ] **Step 3: `EquitySummary` (5-card strip)**

Write `frontend/src/components/portfolio/EquitySummary.tsx`:

```tsx
import type { WalletSnapshot } from '@/types';

export function EquitySummary({ wallet }: { wallet: WalletSnapshot | null }) {
    return (
        <div className="mb-4 grid grid-cols-5 gap-2.5">
            <Card label="Equity" value={fmtUsd(wallet?.equity)} />
            <Card label="Avail. margin" value={fmtUsd(wallet?.available)} />
            <Card label="Open P&L" value={fmtUsd(wallet?.openPnl)} colorize />
            <Card label="Today realized" value={fmtUsd(wallet?.todayRealized)} colorize />
            <Card label="Positions" value={wallet ? `${wallet.positionsOpen}` : '—'} />
        </div>
    );
}

function Card({ label, value, colorize = false }: { label: string; value: string; colorize?: boolean }) {
    const numeric = parseFloat(value.replace(/[^-0-9.]/g, ''));
    const color = colorize && !Number.isNaN(numeric)
        ? (numeric > 0 ? '#4ade80' : numeric < 0 ? '#ef4444' : '#ffffff')
        : '#ffffff';
    return (
        <div className="rounded bg-[#0f1116] p-3">
            <div className="text-[10px] uppercase text-gray-500">{label}</div>
            <div className="text-lg font-semibold" style={{ color }}>{value}</div>
        </div>
    );
}

function fmtUsd(n: number | null | undefined): string {
    if (n == null || Number.isNaN(n)) return '—';
    const sign = n > 0 ? '+' : '';
    return `${sign}$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
```

- [ ] **Step 4: `ExchangeCard` shell — ties header + equity + positions-placeholder + recent-placeholder**

Write `frontend/src/components/portfolio/ExchangeCard.tsx`:

```tsx
import { useState } from 'react';
import type { ExchangeAccount } from '@/types';
import { useExecutionStream } from '@/hooks/useExecutionStream';
import { ExchangeCardHeader } from './ExchangeCardHeader';
import { EquitySummary } from './EquitySummary';

interface Props {
    account: ExchangeAccount;
    onPatch: (id: number, fields: Partial<ExchangeAccount>) => Promise<{ success: boolean; error?: string }>;
}

export function ExchangeCard({ account, onPatch }: Props) {
    const stream = useExecutionStream(account.id);
    const [pendingToggle, setPendingToggle] = useState(false);

    const handleAutoTrade = async () => {
        setPendingToggle(true);
        // Task 8 will gate this with FirstTimeAutoTradeModal on first enable.
        await onPatch(account.id, { autoTradeEnabled: !account.autoTradeEnabled });
        setPendingToggle(false);
    };

    const handleKillSwitch = async () => {
        setPendingToggle(true);
        await onPatch(account.id, { killSwitch: !account.killSwitch });
        setPendingToggle(false);
    };

    const handleSettings = () => {
        // Wired in Task 12.
        console.log('settings panel — Task 12');
    };

    return (
        <div className="rounded-lg border border-[#1c1f27] bg-[#141820] p-4">
            <ExchangeCardHeader
                account={account}
                connectionState={stream.connectionState}
                secondsSinceUpdate={stream.secondsSinceUpdate}
                onAutoTradeToggle={handleAutoTrade}
                onKillSwitchClick={handleKillSwitch}
                onSettingsClick={handleSettings}
            />
            <EquitySummary wallet={stream.wallet} />
            <div className="mb-2 text-[11px] uppercase tracking-wide text-gray-500">Open positions</div>
            <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">(OpenPositionsTable renders here in Task 5)</div>
            <div className="mt-4 mb-2 text-[11px] uppercase tracking-wide text-gray-500">Recent closed (last 24h)</div>
            <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">(RecentTradesList renders here in Task 10)</div>
        </div>
    );
}
```

- [ ] **Step 5: Wire `ExchangeCard` into `ExchangeAccountsSection`**

Replace the `ExchangeCardPlaceholder` usage in `frontend/src/components/portfolio/ExchangeAccountsSection.tsx`:

```tsx
import { useState } from 'react';
import { useExecutionAccounts } from '@/hooks/useExecutionAccounts';
import { AddExchangeButton } from './AddExchangeButton';
import { ExchangeSetupModal } from './ExchangeSetupModal';
import { ExchangeCard } from './ExchangeCard';

export function ExchangeAccountsSection() {
    const { accounts, loading, create, patch } = useExecutionAccounts();
    const [showSetup, setShowSetup] = useState(false);

    if (loading) {
        return <div className="rounded-lg bg-[#141820] p-6 text-center text-xs text-gray-500">Loading exchanges…</div>;
    }

    return (
        <div className="flex flex-col gap-3">
            {accounts.length === 0 && <AddExchangeButton onClick={() => setShowSetup(true)} />}
            {accounts.map(account => (
                <ExchangeCard key={account.id} account={account} onPatch={patch} />
            ))}
            {accounts.length > 0 && (
                <button
                    onClick={() => setShowSetup(true)}
                    className="rounded-lg border border-dashed border-[#333] bg-[#141820] py-3 text-xs text-gray-500 hover:border-[#1a73e8] hover:text-gray-300">
                    + Add another exchange
                </button>
            )}
            {showSetup && (
                <ExchangeSetupModal
                    mainnetEnabled={false}
                    onClose={() => setShowSetup(false)}
                    onSubmit={create}
                />
            )}
        </div>
    );
}
```

- [ ] **Step 6: Smoke verify**

`npm run build`. Re-apply the temp mount from Task 3 Step 5 if removed.

In browser:
- ExchangeCard renders with the Bybit logo header, connection dot (amber pulsing on load, green once WS opens), auto-trade toggle (off), KILL SWITCH button (red, since account starts armed), Settings gear.
- EquitySummary shows 5 cards; numbers populate within 1-2s after mount (REST fetch).
- Click auto-trade toggle → account refreshes, toggle slides green, button state updates.
- Click KILL SWITCH → button flips to green DISARM. Click again → back to red KILL SWITCH.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/portfolio/ConnectionIndicator.tsx \
        frontend/src/components/portfolio/ExchangeCardHeader.tsx \
        frontend/src/components/portfolio/EquitySummary.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx \
        frontend/src/components/portfolio/ExchangeAccountsSection.tsx
git commit -m "feat(frontend): ExchangeCard header + EquitySummary + ConnectionIndicator"
```

---

## Task 5: `OpenPositionsTable` (rows + detector badges + trail indicator)

**Files:**
- Create: `frontend/src/components/portfolio/OpenPositionsTable.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — replace positions placeholder

- [ ] **Step 1: `OpenPositionsTable`**

Write `frontend/src/components/portfolio/OpenPositionsTable.tsx`:

```tsx
import type { ExecutionPosition } from '@/types';
import { MoreHorizontal } from 'lucide-react';

interface Props {
    positions: ExecutionPosition[];
    livePrices: Record<string, number>;
    onRowMenu: (position: ExecutionPosition, anchor: HTMLElement) => void;
    onWhyClick: (position: ExecutionPosition) => void;
}

const GRID = 'grid-cols-[100px_60px_90px_90px_90px_90px_80px_1fr_24px]';

export function OpenPositionsTable({ positions, livePrices, onRowMenu, onWhyClick }: Props) {
    if (positions.length === 0) {
        return (
            <div className="rounded bg-[#0f1116] p-6 text-center text-[11px] text-gray-500">
                No open positions. Signal-driven entries appear here once the auto-trade toggle is armed.
            </div>
        );
    }

    return (
        <div className="overflow-hidden rounded bg-[#141820]">
            <div className={`grid ${GRID} bg-[#0f1116] px-3 py-2.5 text-[10px] uppercase tracking-wide text-gray-500`}>
                <div>Symbol</div><div>Side</div><div>Entry</div><div>Current</div>
                <div>Stop</div><div>Target</div><div>P&L</div><div>Why</div><div></div>
            </div>
            {positions.map(p => (
                <PositionRow
                    key={p.id}
                    position={p}
                    livePrice={livePrices[p.symbol]}
                    onMenu={(a) => onRowMenu(p, a)}
                    onWhy={() => onWhyClick(p)}
                />
            ))}
        </div>
    );
}

function PositionRow({ position, livePrice, onMenu, onWhy }: {
    position: ExecutionPosition;
    livePrice: number | undefined;
    onMenu: (anchor: HTMLElement) => void;
    onWhy: () => void;
}) {
    const current = livePrice ?? position.entryPrice ?? 0;
    const isLong = position.direction === 'LONG';
    const entry = position.entryPrice ?? 0;
    const stop = position.dynamicStopPrice ?? position.stopPrice;
    const target = position.targetPrice;
    const riskDist = Math.abs(entry - position.stopPrice);
    const pnlDist = isLong ? current - entry : entry - current;
    const rMultiple = riskDist > 0 ? pnlDist / riskDist : 0;
    const pnlUsd = pnlDist * (position.qty ?? 0);
    const pnlColor = pnlDist > 0 ? 'text-[#4ade80]' : pnlDist < 0 ? 'text-[#ef4444]' : 'text-white';
    const sideColor = isLong ? 'bg-[#4ade80]/15 text-[#4ade80]' : 'bg-[#ef4444]/15 text-[#ef4444]';
    const detector = (position.strategy ?? 'DIM').toLowerCase();
    const detectorBadge =
        detector.includes('liquidity') ? { code: 'LS', bg: '#1a73e8' } :
        detector.includes('trend') ? { code: 'TC', bg: '#8b5cf6' } :
        { code: 'DIM', bg: '#555' };
    const trailActive = position.trailTriggeredAt != null && position.dynamicStopPrice != null && position.dynamicStopPrice !== position.stopPrice;

    return (
        <div className={`grid ${GRID} items-center border-t border-[#1c1f27] px-3 py-3 text-[11px] text-white`}>
            <div className="font-semibold">{position.symbol}</div>
            <div><span className={`rounded px-2 py-[1px] text-[9px] font-semibold ${sideColor}`}>{position.direction}</span></div>
            <div>{fmt(entry)}</div>
            <div>{fmt(current)}</div>
            <div className={trailActive ? 'text-[#f7a600]' : ''}>
                {fmt(stop)}
                {trailActive && <span className="ml-1 text-[9px] text-gray-500">TRAIL +{position.trailHighestR.toFixed(1)}R</span>}
            </div>
            <div>{fmt(target)}</div>
            <div className={`${pnlColor} font-semibold`}>
                {rMultiple >= 0 ? '+' : ''}{rMultiple.toFixed(1)}R · {pnlUsd >= 0 ? '+' : ''}${Math.abs(pnlUsd).toFixed(0)}
            </div>
            <div className="text-[10px]">
                <button onClick={onWhy} className="mr-1 rounded px-1.5 py-[1px] text-white" style={{ backgroundColor: detectorBadge.bg }}>
                    {detectorBadge.code}
                </button>
                <span className="text-gray-500">alignment —</span>
            </div>
            <div className="text-right">
                <button
                    onClick={e => onMenu(e.currentTarget)}
                    className="text-gray-500 hover:text-white">
                    <MoreHorizontal size={14} />
                </button>
            </div>
        </div>
    );
}

function fmt(n: number): string {
    if (!Number.isFinite(n) || n === 0) return '—';
    const abs = Math.abs(n);
    if (abs >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
    if (abs >= 1) return n.toFixed(2);
    return n.toFixed(4);
}
```

- [ ] **Step 2: Wire into `ExchangeCard`**

Replace the positions placeholder block in `frontend/src/components/portfolio/ExchangeCard.tsx`:

```tsx
import { useState } from 'react';
import type { ExchangeAccount, ExecutionPosition } from '@/types';
import { useExecutionStream } from '@/hooks/useExecutionStream';
import { useWebSocket } from '@/hooks/useWebSocket';
import { ExchangeCardHeader } from './ExchangeCardHeader';
import { EquitySummary } from './EquitySummary';
import { OpenPositionsTable } from './OpenPositionsTable';

interface Props {
    account: ExchangeAccount;
    onPatch: (id: number, fields: Partial<ExchangeAccount>) => Promise<{ success: boolean; error?: string }>;
}

export function ExchangeCard({ account, onPatch }: Props) {
    const stream = useExecutionStream(account.id);
    const [livePrices, setLivePrices] = useState<Record<string, number>>({});

    useWebSocket({
        onPrices: (prices: Array<{ symbol: string; price: number }>) => {
            if (!Array.isArray(prices)) return;
            setLivePrices(prev => {
                const next = { ...prev };
                for (const p of prices) {
                    if (p.symbol && p.price) next[p.symbol] = p.price;
                }
                return next;
            });
        },
    });

    const handleAutoTrade = () => onPatch(account.id, { autoTradeEnabled: !account.autoTradeEnabled });
    const handleKillSwitch = () => onPatch(account.id, { killSwitch: !account.killSwitch });
    const handleSettings = () => { console.log('settings panel — Task 12'); };
    const handleRowMenu = (p: ExecutionPosition, anchor: HTMLElement) => {
        console.log('row menu — Task 6', p.id, anchor);
    };
    const handleWhy = (p: ExecutionPosition) => {
        console.log('why modal — Task 7', p.id);
    };

    return (
        <div className="rounded-lg border border-[#1c1f27] bg-[#141820] p-4">
            <ExchangeCardHeader
                account={account}
                connectionState={stream.connectionState}
                secondsSinceUpdate={stream.secondsSinceUpdate}
                onAutoTradeToggle={handleAutoTrade}
                onKillSwitchClick={handleKillSwitch}
                onSettingsClick={handleSettings}
            />
            <EquitySummary wallet={stream.wallet} />
            <div className="mb-2 text-[11px] uppercase tracking-wide text-gray-500">Open positions</div>
            <OpenPositionsTable
                positions={stream.positions}
                livePrices={livePrices}
                onRowMenu={handleRowMenu}
                onWhyClick={handleWhy}
            />
            <div className="mt-4 mb-2 text-[11px] uppercase tracking-wide text-gray-500">Recent closed (last 24h)</div>
            <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">(RecentTradesList renders here in Task 10)</div>
        </div>
    );
}
```

- [ ] **Step 3: Smoke verify**

Inject a test position so the table renders non-empty:
```bash
curl -X POST http://localhost:31087/api/execution/test/inject-signal \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"BTCUSDT","direction":"LONG","strategy":"liquidity-sweep","entryPrice":"95000","stopPrice":"94500","targetPrice":"96500"}'
```

In browser:
- Open positions table shows 1 row with BTCUSDT / LONG (green badge) / detector badge `LS` blue.
- Click LS → console logs `why modal — Task 7 <id>`.
- Click ⋯ → console logs `row menu — Task 6 <id>`.
- P&L cell color flips green/red based on live price movement.
- Network tab: signal-service WS `ticker` frames update `livePrices` every few seconds; position's `Current` column updates in real time.

Revoke test signal by closing via curl:
```bash
curl -X POST http://localhost:31080/api/execution/accounts/1/trades/<tradeId>/close
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/OpenPositionsTable.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): OpenPositionsTable with detector badges + trail indicator"
```

---

## Task 6: Row `⋯` menu (View chart / Why / Close at market)

**Files:**
- Create: `frontend/src/components/portfolio/PositionRowMenu.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — wire `onRowMenu` to open the popover and `onCloseTrade` callback

- [ ] **Step 1: `PositionRowMenu`**

Write `frontend/src/components/portfolio/PositionRowMenu.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react';
import type { ExecutionPosition } from '@/types';

interface Props {
    position: ExecutionPosition;
    anchor: HTMLElement;
    onClose: () => void;
    onViewChart: () => void;
    onViewWhy: () => void;
    onCloseAtMarket: () => void;
}

export function PositionRowMenu({ position, anchor, onClose, onViewChart, onViewWhy, onCloseAtMarket }: Props) {
    const ref = useRef<HTMLDivElement>(null);
    const [confirming, setConfirming] = useState(false);

    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node) && !anchor.contains(e.target as Node)) {
                onClose();
            }
        };
        const escHandler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('mousedown', handler);
        document.addEventListener('keydown', escHandler);
        return () => {
            document.removeEventListener('mousedown', handler);
            document.removeEventListener('keydown', escHandler);
        };
    }, [anchor, onClose]);

    const rect = anchor.getBoundingClientRect();
    const style: React.CSSProperties = {
        position: 'fixed',
        top: rect.bottom + 4,
        left: Math.max(8, rect.right - 160),
        width: 160,
    };

    return (
        <div
            ref={ref}
            style={style}
            className="z-50 rounded border border-[#2a3040] bg-[#0a0d14] py-1 shadow-lg">
            <button
                onClick={() => { onViewChart(); onClose(); }}
                className="block w-full px-3 py-1.5 text-left text-[11px] text-gray-300 hover:bg-[#141820]">
                View in chart
            </button>
            <button
                onClick={() => { onViewWhy(); onClose(); }}
                className="block w-full px-3 py-1.5 text-left text-[11px] text-gray-300 hover:bg-[#141820]">
                Why this trade?
            </button>
            {confirming ? (
                <div className="border-t border-[#1c1f27] pt-2">
                    <div className="px-3 text-[10px] text-gray-400">
                        Close {position.symbol} {position.direction} @ market?
                    </div>
                    <div className="mt-2 flex gap-1 px-2 pb-1">
                        <button
                            onClick={onClose}
                            className="flex-1 rounded bg-[#222] px-2 py-1 text-[10px] text-gray-300">
                            Cancel
                        </button>
                        <button
                            onClick={() => { onCloseAtMarket(); onClose(); }}
                            className="flex-1 rounded bg-[#ef4444] px-2 py-1 text-[10px] font-semibold text-white">
                            Close
                        </button>
                    </div>
                </div>
            ) : (
                <button
                    onClick={() => setConfirming(true)}
                    className="mt-1 block w-full border-t border-[#1c1f27] px-3 py-1.5 text-left text-[11px] text-[#ef4444] hover:bg-[#141820]">
                    Close at market
                </button>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Wire into `ExchangeCard`**

In `frontend/src/components/portfolio/ExchangeCard.tsx`, add imports and state:

```tsx
import { PositionRowMenu } from './PositionRowMenu';
import { api } from '@/lib/api';
```

Inside the component, add:

```tsx
const [rowMenu, setRowMenu] = useState<{ position: ExecutionPosition; anchor: HTMLElement } | null>(null);

const handleRowMenu = (position: ExecutionPosition, anchor: HTMLElement) => {
    setRowMenu({ position, anchor });
};

const handleViewChart = (_position: ExecutionPosition) => {
    console.log('chart modal — existing TradeChartModal scaffold, wiring in Task 13');
};

const handleCloseAtMarket = async (position: ExecutionPosition) => {
    const res = await api.execution.closeTrade(account.id, position.id);
    if (res.error) {
        alert(`Close failed: ${res.error}`);
        return;
    }
    await stream.refresh();
};
```

Render the menu at the bottom of the JSX:

```tsx
{rowMenu && (
    <PositionRowMenu
        position={rowMenu.position}
        anchor={rowMenu.anchor}
        onClose={() => setRowMenu(null)}
        onViewChart={() => handleViewChart(rowMenu.position)}
        onViewWhy={() => handleWhy(rowMenu.position)}
        onCloseAtMarket={() => handleCloseAtMarket(rowMenu.position)}
    />
)}
```

- [ ] **Step 3: Smoke verify**

Re-inject a BTCUSDT test position if none present. In browser:
- Click ⋯ on the row → popover anchored below the button with 3 items.
- Click `View in chart` → console logs "chart modal — Task 13".
- Click `Why this trade?` → console logs "why modal — Task 7" (wired in Task 7).
- Click `Close at market` → inline confirmation appears. Click Cancel → menu closes. Click again → confirmation → Close → `closeTrade` POST fires, returns 200, position row disappears after next WS refresh.
- Click outside menu → closes.
- Press Esc → closes.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/PositionRowMenu.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): PositionRowMenu with view-chart / why / close-at-market"
```

---

## Task 7: `WhyModal`

**Files:**
- Create: `frontend/src/components/portfolio/WhyModal.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — wire

- [ ] **Step 1: `WhyModal`**

Write `frontend/src/components/portfolio/WhyModal.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { api } from '@/lib/api';
import type { ExecutionPosition, WhyView } from '@/types';

interface Props {
    accountId: number;
    position: ExecutionPosition;
    onClose: () => void;
}

export function WhyModal({ accountId, position, onClose }: Props) {
    const [why, setWhy] = useState<WhyView | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            const data = await api.execution.getWhy(accountId, position.id);
            if (!cancelled) { setWhy(data); setLoading(false); }
        })();
        return () => { cancelled = true; };
    }, [accountId, position.id]);

    useEffect(() => {
        const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('keydown', esc);
        return () => document.removeEventListener('keydown', esc);
    }, [onClose]);

    const riskDist = position.entryPrice != null ? Math.abs(position.entryPrice - position.stopPrice) : null;
    const rewardDist = position.entryPrice != null ? Math.abs(position.targetPrice - position.entryPrice) : null;
    const rr = (riskDist != null && rewardDist != null && riskDist > 0) ? (rewardDist / riskDist) : null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={onClose}>
            <div
                className="max-h-[85vh] w-[560px] overflow-y-auto rounded-lg border border-[#2a3040] bg-[#141820] p-5"
                onClick={e => e.stopPropagation()}>
                <div className="mb-4 flex items-center justify-between">
                    <div>
                        <div className="text-sm font-semibold text-white">Why this trade?</div>
                        <div className="text-[10px] text-gray-400">
                            {position.symbol} {position.direction} · strategy: {position.strategy ?? 'dimension'} · signalId: {position.signalId ?? '—'}
                        </div>
                    </div>
                    <button onClick={onClose} className="text-gray-400 hover:text-white"><X size={16} /></button>
                </div>

                <Section title="Trade levels">
                    <KVRow label="Entry" value={fmt(position.entryPrice)} />
                    <KVRow label="Stop" value={fmt(position.stopPrice)} />
                    <KVRow label="Target" value={fmt(position.targetPrice)} />
                    <KVRow label="Qty" value={position.qty != null ? position.qty.toString() : '—'} />
                    <KVRow label="Leverage" value={position.leverage != null ? `${position.leverage}×` : '—'} />
                    <KVRow label="R:R" value={rr != null ? `${rr.toFixed(2)}:1` : '—'} />
                </Section>

                <Section title="Trail state">
                    <KVRow label="Current rung" value={`${position.trailHighestR.toFixed(1)}R`} />
                    <KVRow label="Dynamic stop" value={fmt(position.dynamicStopPrice)} />
                    <KVRow label="Trail activated" value={position.trailTriggeredAt ?? '—'} />
                </Section>

                <Section title="Signal context (from /why endpoint)">
                    {loading && <div className="text-[11px] text-gray-500">Loading…</div>}
                    {!loading && why && (
                        <pre className="overflow-x-auto rounded bg-[#0f1116] p-3 text-[10px] text-gray-300">
{JSON.stringify(why.signalSnapshot, null, 2)}
                        </pre>
                    )}
                    {!loading && !why && (
                        <div className="text-[11px] text-gray-500">Could not load signal context.</div>
                    )}
                </Section>
            </div>
        </div>
    );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div className="mb-4">
            <div className="mb-2 text-[10px] uppercase tracking-wide text-gray-500">{title}</div>
            <div className="rounded bg-[#0f1116] p-3 text-[11px] text-white">{children}</div>
        </div>
    );
}

function KVRow({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex justify-between border-b border-[#1c1f27] py-1 last:border-0">
            <span className="text-gray-400">{label}</span>
            <span className="font-medium">{value}</span>
        </div>
    );
}

function fmt(n: number | null): string {
    if (n == null || !Number.isFinite(n)) return '—';
    const abs = Math.abs(n);
    if (abs >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
    if (abs >= 1) return n.toFixed(2);
    return n.toFixed(4);
}
```

- [ ] **Step 2: Wire into `ExchangeCard`**

In `frontend/src/components/portfolio/ExchangeCard.tsx`, add:

```tsx
import { WhyModal } from './WhyModal';
```

Add state + handler:

```tsx
const [whyFor, setWhyFor] = useState<ExecutionPosition | null>(null);
const handleWhy = (position: ExecutionPosition) => setWhyFor(position);
```

Render:

```tsx
{whyFor && (
    <WhyModal
        accountId={account.id}
        position={whyFor}
        onClose={() => setWhyFor(null)}
    />
)}
```

- [ ] **Step 3: Smoke verify**

With a test position present, click the detector badge (LS / TC / DIM) in a row → modal opens with trade-levels table, trail-state table, and signal-context JSON pretty-printed (phase 1 stub returns `{note: "..."}`). Click outside / Escape / × → closes.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/WhyModal.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): WhyModal for per-position signal context"
```

---

## Task 8: First-time auto-trade activation modal

**Files:**
- Create: `frontend/src/components/portfolio/FirstTimeAutoTradeModal.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — gate `handleAutoTrade` on first enable

- [ ] **Step 1: `FirstTimeAutoTradeModal`**

Write `frontend/src/components/portfolio/FirstTimeAutoTradeModal.tsx`:

```tsx
import { X } from 'lucide-react';
import type { ExchangeAccount } from '@/types';

interface Props {
    account: ExchangeAccount;
    onConfirm: () => void;
    onCancel: () => void;
}

export function FirstTimeAutoTradeModal({ account, onConfirm, onCancel }: Props) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={onCancel}>
            <div
                className="w-[460px] rounded-lg border border-[#2a3040] bg-[#141820] p-5"
                onClick={e => e.stopPropagation()}>
                <div className="mb-3 flex items-center justify-between">
                    <div className="text-sm font-semibold text-white">Enable live trading?</div>
                    <button onClick={onCancel} className="text-gray-400 hover:text-white"><X size={16} /></button>
                </div>
                <div className="mb-4 text-[12px] leading-relaxed text-gray-300">
                    You're about to enable auto-trade on <span className="font-semibold text-white">Bybit {account.environment}</span>.
                    STRONG_BUY / STRONG_SELL signals will open real orders with real money.
                </div>
                <div className="mb-4 rounded bg-[#0f1116] p-3 text-[11px]">
                    <KV label="Risk per trade" value={`${account.riskPercent}% of equity`} />
                    <KV label="Default leverage" value={`${account.defaultLeverage}×`} />
                    <KV label="Max concurrent" value={`${account.maxConcurrentPositions}`} />
                    <KV label="Daily loss halt" value={`${account.maxDailyLossPercent}%`} />
                    <KV label="Signal max age" value={`${account.signalAgeSeconds}s`} />
                </div>
                <div className="flex gap-2">
                    <button onClick={onCancel} className="flex-1 rounded bg-[#222] px-3 py-2 text-xs text-gray-300 hover:bg-[#2a2f38]">Cancel</button>
                    <button onClick={onConfirm} className="flex-1 rounded bg-[#4ade80] px-3 py-2 text-xs font-semibold text-black hover:bg-[#6ee498]">
                        I understand, activate
                    </button>
                </div>
            </div>
        </div>
    );
}

function KV({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex justify-between border-b border-[#1c1f27] py-1 text-white last:border-0">
            <span className="text-gray-400">{label}</span>
            <span className="font-medium">{value}</span>
        </div>
    );
}
```

- [ ] **Step 2: Gate auto-trade enable in `ExchangeCard`**

Replace `handleAutoTrade` in `frontend/src/components/portfolio/ExchangeCard.tsx`:

```tsx
import { FirstTimeAutoTradeModal } from './FirstTimeAutoTradeModal';

// inside the component:
const [showAutoTradeConfirm, setShowAutoTradeConfirm] = useState(false);

const firstTimeKey = `execution.auto-trade-confirmed.${account.id}`;

const handleAutoTrade = () => {
    if (account.autoTradeEnabled) {
        // Disabling never needs a confirmation
        onPatch(account.id, { autoTradeEnabled: false });
        return;
    }
    const alreadyConfirmed = window.localStorage.getItem(firstTimeKey) === 'true';
    if (alreadyConfirmed) {
        onPatch(account.id, { autoTradeEnabled: true });
        return;
    }
    setShowAutoTradeConfirm(true);
};

const confirmAutoTradeEnable = () => {
    window.localStorage.setItem(firstTimeKey, 'true');
    setShowAutoTradeConfirm(false);
    onPatch(account.id, { autoTradeEnabled: true });
};
```

Render at bottom of JSX:

```tsx
{showAutoTradeConfirm && (
    <FirstTimeAutoTradeModal
        account={account}
        onConfirm={confirmAutoTradeEnable}
        onCancel={() => setShowAutoTradeConfirm(false)}
    />
)}
```

- [ ] **Step 3: Smoke verify**

With an account already created (auto-trade off, localStorage flag absent):
- Click auto-trade toggle → modal opens with account's risk/leverage/etc.
- Click Cancel → modal closes, toggle stays off.
- Click toggle again → modal reopens.
- Click `I understand, activate` → modal closes, toggle slides green, PATCH fires, `localStorage.getItem('execution.auto-trade-confirmed.<id>')` === `"true"`.
- Toggle off → silent (no modal).
- Toggle on again → silent (flag already set).

To re-test the first-time gate, run in DevTools Console: `localStorage.removeItem('execution.auto-trade-confirmed.1')`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/FirstTimeAutoTradeModal.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): FirstTimeAutoTradeModal gates first auto-trade enable"
```

---

## Task 9: Kill-switch engaged visual state

**Files:**
- Create: `frontend/src/components/portfolio/KillSwitchBanner.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — render banner + dim body when `killSwitch=true`

- [ ] **Step 1: `KillSwitchBanner`**

Write `frontend/src/components/portfolio/KillSwitchBanner.tsx`:

```tsx
interface Props {
    onDisarm: () => void;
}

export function KillSwitchBanner({ onDisarm }: Props) {
    return (
        <div className="-mx-4 -mt-4 mb-4 flex items-center justify-between rounded-t-lg bg-[#3a1a1a] px-4 py-2.5 text-[11px] font-semibold text-[#ef4444]">
            <span>🛑 KILL SWITCH ACTIVE — no new positions</span>
            <button
                onClick={onDisarm}
                className="rounded bg-[#ef4444] px-3 py-1 text-[10px] font-semibold text-white hover:bg-[#dc2626]">
                DISARM
            </button>
        </div>
    );
}
```

- [ ] **Step 2: Conditionally render in `ExchangeCard`**

Modify the outer wrapper + body in `frontend/src/components/portfolio/ExchangeCard.tsx`:

```tsx
import { KillSwitchBanner } from './KillSwitchBanner';

// inside the return — replace existing outer div and its children:
const bodyClass = account.killSwitch
    ? 'opacity-[0.85] [filter:grayscale(0.3)]'
    : '';
const cardBorder = account.killSwitch ? 'border-[#ef4444]' : 'border-[#1c1f27]';

return (
    <div className={`rounded-lg border ${cardBorder} bg-[#141820] p-4`}>
        {account.killSwitch && <KillSwitchBanner onDisarm={() => onPatch(account.id, { killSwitch: false })} />}
        <div className={bodyClass}>
            <ExchangeCardHeader
                account={account}
                connectionState={stream.connectionState}
                secondsSinceUpdate={stream.secondsSinceUpdate}
                onAutoTradeToggle={handleAutoTrade}
                onKillSwitchClick={handleKillSwitch}
                onSettingsClick={handleSettings}
            />
            <EquitySummary wallet={stream.wallet} />
            <div className="mb-2 text-[11px] uppercase tracking-wide text-gray-500">Open positions</div>
            <OpenPositionsTable
                positions={stream.positions}
                livePrices={livePrices}
                onRowMenu={handleRowMenu}
                onWhyClick={handleWhy}
            />
            {account.killSwitch && (
                <div className="mt-3 rounded bg-[#0f1116] p-2 text-center text-[10px] text-gray-500">
                    positions still tracked — only NEW signals blocked
                </div>
            )}
            <div className="mt-4 mb-2 text-[11px] uppercase tracking-wide text-gray-500">Recent closed (last 24h)</div>
            <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">(RecentTradesList renders here in Task 10)</div>
        </div>
        {rowMenu && (
            <PositionRowMenu
                position={rowMenu.position}
                anchor={rowMenu.anchor}
                onClose={() => setRowMenu(null)}
                onViewChart={() => handleViewChart(rowMenu.position)}
                onViewWhy={() => handleWhy(rowMenu.position)}
                onCloseAtMarket={() => handleCloseAtMarket(rowMenu.position)}
            />
        )}
        {whyFor && (
            <WhyModal
                accountId={account.id}
                position={whyFor}
                onClose={() => setWhyFor(null)}
            />
        )}
        {showAutoTradeConfirm && (
            <FirstTimeAutoTradeModal
                account={account}
                onConfirm={confirmAutoTradeEnable}
                onCancel={() => setShowAutoTradeConfirm(false)}
            />
        )}
    </div>
);
```

- [ ] **Step 3: Smoke verify**

New account lands with `killSwitch=true`:
- Red border around card. Red banner at top: `🛑 KILL SWITCH ACTIVE — no new positions` + `DISARM` button.
- Card body visibly dimmed (lighter grayscale).
- Footer note: `positions still tracked — only NEW signals blocked`.
- Click `DISARM` in banner → PATCH fires with `{killSwitch: false}` → banner disappears, dim removed, card border returns to subtle.
- Header KILL SWITCH button → re-engaging restores the full banner state.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/KillSwitchBanner.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): kill-switch engaged banner + dim + DISARM"
```

---

## Task 10: `RecentTradesList` (24h closed with badges)

**Files:**
- Create: `frontend/src/components/portfolio/RecentTradesList.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — replace recent-trades placeholder

- [ ] **Step 1: `RecentTradesList`**

Write `frontend/src/components/portfolio/RecentTradesList.tsx`:

```tsx
import type { ExecutionTrade } from '@/types';

interface Props {
    trades: ExecutionTrade[];
    onShowAll: () => void;
}

type ExitBadge = { code: string; bg: string; fg: string };

function badgeFor(exitReason: string | null): ExitBadge {
    switch (exitReason) {
        case 'TARGET': return { code: 'TARGET', bg: 'rgba(74,222,128,0.15)', fg: '#4ade80' };
        case 'TRAIL':
        case 'DYNAMIC_STOP': return { code: 'TRAIL', bg: 'rgba(247,166,0,0.15)', fg: '#f7a600' };
        case 'STOP':
        case 'INITIAL_STOP': return { code: 'STOP', bg: 'rgba(239,68,68,0.15)', fg: '#ef4444' };
        case 'EXPIRED': return { code: 'EXPIRED', bg: 'rgba(136,136,136,0.15)', fg: '#888888' };
        case 'FLIP_CLOSE': return { code: 'FLIP', bg: 'rgba(26,115,232,0.15)', fg: '#1a73e8' };
        case 'MANUAL': return { code: 'MANUAL', bg: 'rgba(139,92,246,0.15)', fg: '#8b5cf6' };
        case 'KILL': return { code: 'KILL', bg: 'rgba(239,68,68,0.15)', fg: '#ef4444' };
        default: return { code: exitReason ?? '—', bg: 'rgba(136,136,136,0.15)', fg: '#888888' };
    }
}

export function RecentTradesList({ trades, onShowAll }: Props) {
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    const recent = trades
        .filter(t => t.closedAt != null && new Date(t.closedAt).getTime() >= cutoff)
        .slice(0, 10);

    if (recent.length === 0) {
        return (
            <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">
                No closed trades in the last 24 hours.
            </div>
        );
    }

    return (
        <div className="rounded bg-[#141820] p-3">
            {recent.map((t, i) => {
                const badge = badgeFor(t.exitReason);
                const r = t.realizedRMultiple;
                const pnl = t.realizedPnlUsdt;
                return (
                    <div
                        key={t.id}
                        className={`flex items-center justify-between py-1.5 text-[11px] text-white ${i > 0 ? 'border-t border-[#1c1f27]' : ''}`}>
                        <span>{t.symbol} {t.direction}</span>
                        <span>
                            <span
                                className="mr-2 rounded px-1.5 py-[1px] text-[9px] font-semibold"
                                style={{ backgroundColor: badge.bg, color: badge.fg }}>
                                {badge.code}
                            </span>
                            {r != null && (
                                <span className={r >= 0 ? 'text-[#4ade80]' : 'text-[#ef4444]'}>
                                    {r >= 0 ? '+' : ''}{r.toFixed(1)}R
                                </span>
                            )}
                            {pnl != null && (
                                <span className={`ml-1 ${pnl >= 0 ? 'text-[#4ade80]' : 'text-[#ef4444]'}`}>
                                    · {pnl >= 0 ? '+' : ''}${Math.abs(pnl).toFixed(0)}
                                </span>
                            )}
                        </span>
                    </div>
                );
            })}
            <button
                onClick={onShowAll}
                className="mt-2 w-full rounded border-t border-[#1c1f27] pt-2 text-[10px] text-gray-500 hover:text-gray-300">
                see all →
            </button>
        </div>
    );
}
```

- [ ] **Step 2: Wire into `ExchangeCard`**

Replace the recent-closed placeholder:

```tsx
import { RecentTradesList } from './RecentTradesList';

// replace the placeholder block:
<RecentTradesList
    trades={stream.trades}
    onShowAll={() => console.log('full trade ledger — wired in Task 13')}
/>
```

- [ ] **Step 3: Smoke verify**

Close a test trade (from Task 6). Wait ~60s for reconciler to populate `realizedPnlUsdt` + `exitReason`:
```bash
curl 'http://localhost:31080/api/execution/accounts/1/trades?limit=5' | head -20
```

In browser: Recent closed section lists the closed trade with the correct badge (MANUAL for user-close, or TARGET/TRAIL/STOP based on exitReason). R-multiple + PnL USD shown with correct color. `see all →` logs the Task 13 marker.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/RecentTradesList.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): RecentTradesList with exit-reason badges"
```

---

## Task 11: WebSocket reconnect visual polish

**Files:**
- Modify: `frontend/src/components/portfolio/ConnectionIndicator.tsx` — add POLLING FALLBACK pill + dim values
- Modify: `frontend/src/components/portfolio/ExchangeCardHeader.tsx` — add pill to right side when disconnected
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — dim body at `opacity-70` when `connectionState !== 'connected'`

- [ ] **Step 1: Expose `POLLING FALLBACK` pill from header**

Modify `frontend/src/components/portfolio/ExchangeCardHeader.tsx` — add to the right-side cluster before the `Auto-trade` label:

```tsx
{(connectionState === 'reconnecting' || connectionState === 'disconnected') && (
    <span className="rounded bg-[#222] px-2 py-1 text-[9px] uppercase tracking-wide text-gray-400">
        POLLING FALLBACK
    </span>
)}
```

- [ ] **Step 2: Dim card body when not connected**

In `frontend/src/components/portfolio/ExchangeCard.tsx`, modify `bodyClass`:

```tsx
const bodyClass = [
    account.killSwitch ? 'opacity-[0.85] [filter:grayscale(0.3)]' : '',
    stream.connectionState !== 'connected' && !account.killSwitch ? 'opacity-70' : '',
].filter(Boolean).join(' ');
```

- [ ] **Step 3: Smoke verify**

With an account + WS connected: green dot, no pill, body full brightness.

Stop api-gateway briefly:
```bash
docker compose stop api-gateway
```
Within ~5s: dot turns amber pulsing, status line shows `Reconnecting… (last update Xs ago)`, `POLLING FALLBACK` pill appears, body dims to ~70% opacity. Equity numbers continue updating every ~15s from polling REST fetch (even with api-gateway down, IF trade-execution-service is reachable — in the typical setup api-gateway is the only entry, so polling fails too; in that case numbers freeze, which is expected).

```bash
docker compose start api-gateway
```
Within ~5s: dot flips green, pill vanishes, body back to full opacity, refresh kicks.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/ExchangeCardHeader.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): WS-disconnected dim + POLLING FALLBACK pill"
```

---

## Task 12: Settings side-panel

**Files:**
- Create: `frontend/src/components/portfolio/SettingsPanel.tsx`
- Modify: `frontend/src/components/portfolio/ExchangeCard.tsx` — wire `onSettingsClick`

- [ ] **Step 1: `SettingsPanel`**

Write `frontend/src/components/portfolio/SettingsPanel.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import type { ExchangeAccount, UpdateAccountRequest } from '@/types';

interface Props {
    account: ExchangeAccount;
    onClose: () => void;
    onSave: (req: UpdateAccountRequest) => Promise<{ success: boolean; error?: string }>;
}

type FormState = {
    riskPercent: string;
    defaultLeverage: string;
    maxConcurrentPositions: string;
    maxDailyLossPercent: string;
    signalAgeSeconds: string;
    positionMaxAgeHours: string;
    flipPersistenceTicks: string;
};

function fromAccount(a: ExchangeAccount): FormState {
    return {
        riskPercent: String(a.riskPercent),
        defaultLeverage: String(a.defaultLeverage),
        maxConcurrentPositions: String(a.maxConcurrentPositions),
        maxDailyLossPercent: String(a.maxDailyLossPercent),
        signalAgeSeconds: String(a.signalAgeSeconds),
        positionMaxAgeHours: String(a.positionMaxAgeHours),
        flipPersistenceTicks: String(a.flipPersistenceTicks),
    };
}

export function SettingsPanel({ account, onClose, onSave }: Props) {
    const [form, setForm] = useState<FormState>(fromAccount(account));
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('keydown', esc);
        return () => document.removeEventListener('keydown', esc);
    }, [onClose]);

    const setField = (field: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) =>
        setForm(prev => ({ ...prev, [field]: e.target.value }));

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        const diff: UpdateAccountRequest = {};
        const parse = (k: keyof FormState, orig: number) => {
            const n = parseFloat(form[k]);
            if (!Number.isFinite(n) || n === orig) return undefined;
            return n;
        };
        const riskPercent = parse('riskPercent', account.riskPercent);
        const defaultLeverage = parse('defaultLeverage', account.defaultLeverage);
        const maxConcurrentPositions = parse('maxConcurrentPositions', account.maxConcurrentPositions);
        const maxDailyLossPercent = parse('maxDailyLossPercent', account.maxDailyLossPercent);
        const signalAgeSeconds = parse('signalAgeSeconds', account.signalAgeSeconds);
        const positionMaxAgeHours = parse('positionMaxAgeHours', account.positionMaxAgeHours);
        const flipPersistenceTicks = parse('flipPersistenceTicks', account.flipPersistenceTicks);
        if (riskPercent != null) diff.riskPercent = riskPercent;
        if (defaultLeverage != null) diff.defaultLeverage = Math.floor(defaultLeverage);
        if (maxConcurrentPositions != null) diff.maxConcurrentPositions = Math.floor(maxConcurrentPositions);
        if (maxDailyLossPercent != null) diff.maxDailyLossPercent = maxDailyLossPercent;
        if (signalAgeSeconds != null) diff.signalAgeSeconds = Math.floor(signalAgeSeconds);
        if (positionMaxAgeHours != null) diff.positionMaxAgeHours = Math.floor(positionMaxAgeHours);
        if (flipPersistenceTicks != null) diff.flipPersistenceTicks = Math.floor(flipPersistenceTicks);

        if (Object.keys(diff).length === 0) {
            onClose();
            setSaving(false);
            return;
        }
        const result = await onSave(diff);
        setSaving(false);
        if (!result.success) { setError(result.error ?? 'save failed'); return; }
        onClose();
    };

    return (
        <>
            <div className="absolute inset-0 z-40 bg-black/40" onClick={onClose} />
            <div className="absolute right-0 top-0 z-50 h-full w-[260px] border-l border-[#2a3040] bg-[#0a0d14] p-4">
                <div className="mb-3 flex items-center justify-between">
                    <div className="text-[11px] font-semibold text-white">Settings</div>
                    <button onClick={onClose} className="text-gray-400 hover:text-white"><X size={14} /></button>
                </div>
                <Field label="Risk / trade (%)" value={form.riskPercent} onChange={setField('riskPercent')} />
                <Field label="Default leverage (x)" value={form.defaultLeverage} onChange={setField('defaultLeverage')} />
                <Field label="Max concurrent" value={form.maxConcurrentPositions} onChange={setField('maxConcurrentPositions')} />
                <Field label="Daily loss halt (%)" value={form.maxDailyLossPercent} onChange={setField('maxDailyLossPercent')} />
                <Field label="Signal max age (s)" value={form.signalAgeSeconds} onChange={setField('signalAgeSeconds')} />
                <Field label="Position max age (h)" value={form.positionMaxAgeHours} onChange={setField('positionMaxAgeHours')} />
                <Field label="Flip persistence (ticks)" value={form.flipPersistenceTicks} onChange={setField('flipPersistenceTicks')} />
                {error && <div className="mb-2 rounded border border-[#ef4444] bg-[#2d0e0e] p-2 text-[10px] text-[#ef4444]">{error}</div>}
                <div className="mt-3 flex gap-2">
                    <button onClick={onClose} className="flex-1 rounded bg-[#222] px-2 py-2 text-[11px] text-gray-300 hover:bg-[#2a2f38]">Cancel</button>
                    <button
                        onClick={handleSave}
                        disabled={saving}
                        className="flex-1 rounded bg-[#1a73e8] px-2 py-2 text-[11px] font-semibold text-white hover:bg-[#1666d0] disabled:opacity-50">
                        {saving ? 'Saving…' : 'Save'}
                    </button>
                </div>
            </div>
        </>
    );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (e: React.ChangeEvent<HTMLInputElement>) => void }) {
    return (
        <div className="mb-2.5">
            <div className="mb-1 text-[9px] uppercase text-gray-400">{label}</div>
            <input
                type="text"
                value={value}
                onChange={onChange}
                className="w-full rounded bg-[#141820] px-2 py-1.5 text-[11px] text-white"
            />
        </div>
    );
}
```

- [ ] **Step 2: Wire into `ExchangeCard`**

Add state + handler:

```tsx
import { SettingsPanel } from './SettingsPanel';

const [showSettings, setShowSettings] = useState(false);
const handleSettings = () => setShowSettings(true);
```

Change the outer wrapper to `relative` so the absolute-positioned panel anchors correctly:

```tsx
<div className={`relative rounded-lg border ${cardBorder} bg-[#141820] p-4`}>
```

Render panel when open:

```tsx
{showSettings && (
    <SettingsPanel
        account={account}
        onClose={() => setShowSettings(false)}
        onSave={(diff) => onPatch(account.id, diff)}
    />
)}
```

- [ ] **Step 3: Smoke verify**

Click ⚙ Settings in the card header → panel slides in from right covering ~260px. Positions table remains readable behind a dimmed overlay. Change `Risk / trade` to `2.5` → Save → panel closes, PATCH `/api/execution/accounts/1` fires with `{riskPercent: 2.5}`, account refreshes, header unchanged but subsequent injected-signals use the new risk %.

Press Escape or click the dim overlay → panel closes without save.

Enter invalid value (`-1` leverage) → Save → backend returns 400 with validation error → inline error shown, panel stays open.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/portfolio/SettingsPanel.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx
git commit -m "feat(frontend): slide-in SettingsPanel with 7 PATCHable fields"
```

---

## Task 13: Final wire-up + CLAUDE.md

**Files:**
- Modify: `frontend/src/App.tsx` — mount `ExchangeAccountsSection` below `PortfolioTracker` on Portfolio route
- Modify: `frontend/src/components/dashboard/PortfolioTracker.tsx` — revert any temporary imports from Tasks 2-5
- Modify: `CLAUDE.md`

- [ ] **Step 1: Inspect existing App.tsx routing**

```bash
grep -n 'PortfolioTracker' "E:/Projects/Stukans/Prototypes/projectr-x/frontend/src/App.tsx" | head
```

Identify the route / layout where `PortfolioTracker` is currently rendered. The goal is to place `ExchangeAccountsSection` immediately after it so the stacked layout reads: Manual (existing `PortfolioTracker`) → Bybit card (+ any future exchanges) → "+ Add another exchange" button.

- [ ] **Step 2: Mount `ExchangeAccountsSection`**

Find the JSX where `<PortfolioTracker />` is rendered and wrap both components in a shared container:

```tsx
// Before:
<PortfolioTracker />

// After:
<div className="flex flex-col gap-6">
    <PortfolioTracker />
    <ExchangeAccountsSection />
</div>
```

Add the import at the top of `App.tsx`:

```tsx
import { ExchangeAccountsSection } from '@/components/portfolio/ExchangeAccountsSection';
```

- [ ] **Step 3: Wire `onShowAll` to existing `TradeLedger` modal**

The existing `frontend/src/components/dashboard/TradeLedger.tsx` is a full modal. In `ExchangeCard.tsx`, replace the `onShowAll` stub with a modal launcher:

```tsx
import { TradeLedger } from '@/components/dashboard/TradeLedger';

const [showLedger, setShowLedger] = useState(false);

// in JSX near the bottom:
{showLedger && <TradeLedger onClose={() => setShowLedger(false)} accountId={account.id} />}

// replace the RecentTradesList usage:
<RecentTradesList
    trades={stream.trades}
    onShowAll={() => setShowLedger(true)}
/>
```

If `TradeLedger` doesn't accept an `accountId` prop, add it behind a default (`accountId?: number` — when present, filter rows to that account; when absent, existing behavior). If that touches logic you don't want to change now, leave `onShowAll` as a stub and file as a tech-debt item:
```bash
cat > techdebt/frontend/3-1-tradeledger-accountid-filter.md <<'EOF'
# TradeLedger doesn't filter by exchange account

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Trivial |
| Location | frontend/src/components/dashboard/TradeLedger.tsx |
| Found during | Plan 3 Task 13 |
| Date | 2026-04-21 |

## Issue

RecentTradesList.onShowAll is wired to console.log because TradeLedger doesn't accept an accountId prop. Users who click "see all" from a Bybit card should see only that account's trades, not the full cross-signal-service ledger.

## Suggested Solutions

Add optional `accountId?: number` prop. When present, pass to api call (whichever endpoint TradeLedger uses) or filter rows client-side. ~15 min.
EOF
```

- [ ] **Step 4: Chart modal wiring — handle in same way**

`handleViewChart` in `ExchangeCard.tsx` can either launch the existing `TradeChartModal` or remain a stub. If launching:

```tsx
import { TradeChartModal } from '@/components/dashboard/TradeChartModal';

const [chartFor, setChartFor] = useState<ExecutionPosition | null>(null);
const handleViewChart = (position: ExecutionPosition) => setChartFor(position);

// JSX:
{chartFor && <TradeChartModal symbol={chartFor.symbol} onClose={() => setChartFor(null)} />}
```

If `TradeChartModal`'s prop surface doesn't match, file the same tech-debt pattern and keep the `console.log` stub.

- [ ] **Step 5: Update `CLAUDE.md`**

Open `E:/Projects/Stukans/Prototypes/projectr-x/CLAUDE.md`. Find the "Frontend" section (search for `frontend/src/`). Append a note under the existing frontend files description:

```markdown
- `components/portfolio/` — `ExchangeAccountsSection` (stacked exchange cards below Manual), `ExchangeCard` (per-account header + equity + positions + recent), `ExchangeCardHeader`, `EquitySummary`, `OpenPositionsTable`, `PositionRowMenu` (View chart / Why / Close at market), `WhyModal`, `ExchangeSetupModal` (single-step form), `AddExchangeButton` (dashed CTA empty state), `FirstTimeAutoTradeModal` (localStorage-gated), `KillSwitchBanner`, `SettingsPanel` (slide-in right), `ConnectionIndicator` (green/amber-pulsing/red status dot + staleness)
- `hooks/useExecutionStream.ts` — WS to `/ws/execution` + 15s REST polling fallback + staleness counter
- `hooks/useExecutionAccounts.ts` — list/create/patch/delete wrappers over `/api/execution/accounts`
```

- [ ] **Step 6: Final end-to-end smoke**

Rebuild + restart the frontend:
```bash
docker compose build frontend && docker compose up -d --no-deps frontend
```

Walk through the full flow:

1. Open http://localhost:31000. Navigate to Portfolio page.
2. See existing Manual positions card (unchanged).
3. Below it: dashed-border "Connect an exchange" CTA card (no account yet).
4. Click `+ Add Bybit` → modal opens.
5. Enter your demo key + secret (from `.env`) → click Validate + save → modal closes, ExchangeCard appears with kill-switch engaged (red banner, dimmed body, DISARM button).
6. Click DISARM → banner vanishes, body brightens.
7. Click auto-trade toggle → FirstTimeAutoTradeModal with your risk/leverage/etc. → Confirm → toggle slides green.
8. Inject a test signal:
   ```bash
   curl -X POST http://localhost:31087/api/execution/test/inject-signal \
     -H 'Content-Type: application/json' \
     -d '{"symbol":"BTCUSDT","direction":"LONG","strategy":"liquidity-sweep","entryPrice":"95000","stopPrice":"94500","targetPrice":"96500"}'
   ```
   Within 2-3s: position row appears in table with LS blue badge, LONG green side, live current price + P&L.
9. Click LS badge → WhyModal opens with trade levels + signal snapshot.
10. Click ⋯ on the row → popover. Click `Close at market` → confirmation → Close → row disappears.
11. Wait ~60s for reconciler → recent-closed section shows the trade with MANUAL badge.
12. Click ⚙ Settings → panel slides in → change `Risk / trade` to `2.5` → Save → panel closes, account now uses 2.5% risk on next signal.
13. Click KILL SWITCH → full red banner + dim. Inject another signal → blocked → no new row appears. Audit trail:
    ```bash
    curl -s 'http://localhost:31080/api/execution/accounts/1/events?limit=5'
    ```
    → includes `SIGNAL_BLOCKED_KILL_SWITCH` entry.
14. Stop api-gateway → ConnectionIndicator turns amber pulsing, POLLING FALLBACK pill appears, body dims. Restart → back to green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/App.tsx \
        frontend/src/components/portfolio/ExchangeCard.tsx \
        CLAUDE.md
git commit -m "feat(frontend): wire ExchangeAccountsSection into Portfolio page + docs"
```

Optionally (only if tech-debt files were created during Steps 3-4):
```bash
mkdir -p techdebt/frontend
git add techdebt/frontend/
git commit -m "chore(techdebt): flag TradeLedger/TradeChartModal accountId gaps"
```

---

## Self-review checklist (for the implementer)

Before declaring Plan 3 done:

- [ ] `cd frontend && npm run build` completes cleanly. No TypeScript errors.
- [ ] `docker compose build frontend && docker compose up -d --no-deps frontend` brings up a healthy frontend.
- [ ] Portfolio page renders with Manual card on top (unchanged), ExchangeAccountsSection below.
- [ ] With zero accounts: dashed-border CTA card. With one account: ExchangeCard, no CTA, with "+ Add another exchange" button at bottom.
- [ ] Setup modal validates Bybit key server-side (401/400 errors appear inline).
- [ ] New account lands disarmed (kill switch engaged + auto-trade off).
- [ ] FirstTimeAutoTradeModal shows once per account id; subsequent toggles are silent.
- [ ] Kill-switch engaged: red banner, dimmed body, DISARM button works.
- [ ] OpenPositionsTable shows detector badges (LS/TC/DIM) + trail indicator when trail fires.
- [ ] Row ⋯ menu: View chart / Why / Close at market — all three reachable, Close confirms.
- [ ] WhyModal renders for a position; reuses page-consistent chrome.
- [ ] SettingsPanel slides in from right; diff-only PATCH; errors surface inline.
- [ ] RecentTradesList shows last 24h closed with correct exit-reason badges.
- [ ] ConnectionIndicator: green when WS up, amber pulsing + POLLING FALLBACK pill + body dim when down.
- [ ] No secrets logged to browser console.
- [ ] CLAUDE.md updated with Plan 3 entries.
- [ ] `git log --oneline` shows a clean per-task history (13 commits + any tech-debt commits).

---

## What's next

Plan 3 ships the MVP Portfolio extension. Out-of-scope follow-ups:

- **Trade chart modal filter by account**: existing `TradeChartModal` draws signal-service outcomes; doesn't filter by which account actually executed. Tech-debt file recommended.
- **TradeLedger filter by account**: same.
- **Full test harness**: frontend has zero unit tests. Adding vitest + @testing-library/react would be its own feature plan. Most of Plan 3 is visual anyway.
- **Multi-exchange UI**: `ExchangeAccountsSection` already handles a list, but `ExchangeSetupModal` hard-codes `exchange: 'BYBIT'`. When Binance/OKX clients exist on the backend, extend the setup modal with an exchange picker.
- **Manual vs exchange-executed reconciliation** — e.g., if signal-service fires a signal, backend places an order, but user also placed a manual entry on same symbol. Currently both render independently; a future iteration could link them.
- **WhyView join with `signal_outcomes`**: today the `/why` endpoint returns a stub. A backend follow-up should join with `signal_outcomes` table to populate 6-dimension scores, regime, AI analysis. When that ships, `WhyModal.Section "Signal context"` renders the full snapshot instead of the placeholder.
