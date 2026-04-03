import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { useSSE } from './useSSE';
import type { DashboardData, PriceData } from '@/types';

export function useDashboardData() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const result = await api.getDashboard();
      if (result) {
        setData(result);
        setError(null);
      }
    } catch (e) {
      setError('Failed to fetch dashboard data');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 30000);
    return () => clearInterval(interval);
  }, [fetchData]);

  // SSE for real-time price updates
  useSSE<PriceData[]>('/api/stream/prices', (prices) => {
    setData(prev => {
      if (!prev) return prev;
      const updatedPrices = prev.prices.map(p => {
        const update = prices.find((u: any) => u.symbol === p.symbol);
        return update ? { ...p, ...update } : p;
      });
      return { ...prev, prices: updatedPrices, timestamp: new Date().toISOString() };
    });
  });

  return { data, loading, error, refresh: fetchData };
}
