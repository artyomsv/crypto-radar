import { useEffect, useRef, useState, useCallback } from 'react';

interface WebSocketMessage {
  type: 'prices' | 'news' | 'analytics' | 'whales' | 'connected' | 'ack';
  data?: any;
  message?: string;
}

interface UseWebSocketOptions {
  onPrices?: (data: any) => void;
  onNews?: (data: any) => void;
  onAnalytics?: (data: any) => void;
  onWhales?: (data: any) => void;
}

export function useWebSocket(options: UseWebSocketOptions = {}) {
  const wsRef = useRef<WebSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const connect = useCallback(() => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const url = `${protocol}//${host}/ws/crypto`;

    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      setConnected(true);
      console.log('[WS] Connected to CryptoRadar');
    };

    ws.onmessage = (event) => {
      try {
        const msg: WebSocketMessage = JSON.parse(event.data);
        switch (msg.type) {
          case 'prices':
            optionsRef.current.onPrices?.(msg.data);
            break;
          case 'news':
            optionsRef.current.onNews?.(msg.data);
            break;
          case 'analytics':
            optionsRef.current.onAnalytics?.(msg.data);
            break;
          case 'whales':
            optionsRef.current.onWhales?.(msg.data);
            break;
          case 'connected':
            console.log('[WS]', msg.message);
            break;
        }
      } catch (e) {
        console.error('[WS] Parse error:', e);
      }
    };

    ws.onclose = () => {
      setConnected(false);
      console.log('[WS] Disconnected. Reconnecting in 3s...');
      reconnectTimer.current = setTimeout(connect, 3000);
    };

    ws.onerror = (err) => {
      console.error('[WS] Error:', err);
      ws.close();
    };
  }, []);

  useEffect(() => {
    connect();
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connect]);

  const send = useCallback((data: any) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(data));
    }
  }, []);

  return { connected, send };
}
