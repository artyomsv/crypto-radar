import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { useWebSocket } from './useWebSocket';
import type { SignalOverview } from '@/types';

export function useSignalData() {
  const [overview, setOverview] = useState<SignalOverview | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    const data = await api.getSignalOverview();
    if (data) setOverview(data);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 30000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const { connected } = useWebSocket({
    onSignals: (data: any) => {
      if (data?.type === 'overview' && data.data) {
        setOverview(data.data);
      }
    },
  });

  return { overview, loading, connected, refresh: fetchData };
}
