import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { useWebSocket } from './useWebSocket';
import type { WhaleTransaction, WhaleMarketOverview } from '@/types';

export function useWhaleData() {
  const [overview, setOverview] = useState<WhaleMarketOverview | null>(null);
  const [recentTrades, setRecentTrades] = useState<WhaleTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const maxTrades = 100;

  const fetchData = useCallback(async () => {
    try {
      const [analyticsData, tradesData] = await Promise.all([
        api.getWhaleAnalytics(),
        api.getWhaleTransactions(undefined, 50),
      ]);
      if (analyticsData) setOverview(analyticsData);
      if (tradesData) setRecentTrades(tradesData);
    } catch (e) {
      console.error('Failed to fetch whale data:', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 30000);
    return () => clearInterval(interval);
  }, [fetchData]);

  // Live whale trades via WebSocket
  const { connected } = useWebSocket({
    onWhales: (data: any) => {
      if (!data) return;

      if (data.type === 'transaction' && data.data) {
        const tx = data.data as WhaleTransaction;
        setRecentTrades(prev => [tx, ...prev].slice(0, maxTrades));
      }

      if (data.type === 'market_overview' && data.data) {
        setOverview(data.data as WhaleMarketOverview);
      }
    },
  });

  return { overview, recentTrades, loading, connected, refresh: fetchData };
}
