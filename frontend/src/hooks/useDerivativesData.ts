import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { useWebSocket } from './useWebSocket';
import type { DerivativesOverview, LiquidationEvent } from '@/types';

export function useDerivativesData() {
  const [overview, setOverview] = useState<DerivativesOverview | null>(null);
  const [liquidations, setLiquidations] = useState<LiquidationEvent[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    const [overviewData, liqData] = await Promise.all([
      api.getDerivativesOverview(),
      api.getLiquidations(50),
    ]);
    if (overviewData) setOverview(overviewData);
    if (liqData) setLiquidations(liqData);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 30000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const { connected } = useWebSocket({
    onDerivatives: (data: any) => {
      if (data?.type === 'liquidation' && data.data) {
        setLiquidations(prev => [data.data, ...prev].slice(0, 100));
      }
      if (data?.type === 'overview' && data.data) {
        setOverview(data.data);
      }
    },
  });

  return { overview, liquidations, loading, connected, refresh: fetchData };
}
