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

// Poll every 15s when WS is down, every 30s as a safety net when WS is "connected"
// (frontend↔gateway WS being open does not guarantee Bybit private-stream frames
// are flowing through; polling covers that gap).
const POLL_INTERVAL_DISCONNECTED_MS = 15_000;
const POLL_INTERVAL_CONNECTED_MS = 30_000;
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

  // REST polling — fast when WS is down, slow safety net when "connected".
  // WS being open at the gateway does not guarantee Bybit private-stream frames
  // are actually flowing upstream; a slow poll ensures numbers eventually sync.
  useEffect(() => {
    if (accountId == null) return;
    const interval = connectionState === 'connected'
      ? POLL_INTERVAL_CONNECTED_MS
      : POLL_INTERVAL_DISCONNECTED_MS;
    refresh();
    pollTimerRef.current = setInterval(refresh, interval);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
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
      setConnectionState((prev) => (prev === 'connected' ? prev : 'reconnecting'));
      const ws = new WebSocket(wsUrlFor());
      wsRef.current = ws;
      ws.onopen = () => {
        if (cancelled) {
          ws.close();
          return;
        }
        setConnectionState('connected');
        // Kick an initial REST refresh so numbers aren't stale on connect
        refresh();
      };
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          // Any valid JSON frame from the server triggers a REST refresh
          // so all numbers reflect the latest account state.
          if (msg && typeof msg === 'object') {
            refresh();
          }
        } catch {
          // ignore non-JSON frames — server passes raw Bybit JSON through
        }
      };
      ws.onclose = () => {
        if (cancelled) return;
        setConnectionState('reconnecting');
        reconnectTimer = setTimeout(connect, WS_RECONNECT_DELAY_MS);
      };
      ws.onerror = () => {
        // onclose will fire next — no action here
      };
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
