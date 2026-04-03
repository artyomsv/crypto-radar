import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '@/lib/api';
import { useWebSocket } from './useWebSocket';
import type { DashboardData, PriceData } from '@/types';

export interface PriceFlash {
  [symbol: string]: 'up' | 'down' | null;
}

export function useDashboardData() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [priceFlash, setPriceFlash] = useState<PriceFlash>({});
  const prevPrices = useRef<Record<string, number>>({});

  const fetchData = useCallback(async () => {
    try {
      const result = await api.getDashboard();
      if (result) {
        setData(result);
        setError(null);
        // Initialize previous prices
        result.prices?.forEach(p => {
          if (p.price) prevPrices.current[p.symbol] = p.price;
        });
      }
    } catch (e) {
      setError('Failed to fetch dashboard data');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 60000); // Reduce polling since WS handles real-time
    return () => clearInterval(interval);
  }, [fetchData]);

  // WebSocket for real-time price updates
  const { connected } = useWebSocket({
    onPrices: (prices: any[]) => {
      if (!prices || !Array.isArray(prices)) return;

      // Detect price direction for flash animations
      const flashes: PriceFlash = {};
      prices.forEach((update: any) => {
        const prev = prevPrices.current[update.symbol];
        if (prev !== undefined && update.price !== undefined) {
          if (update.price > prev) flashes[update.symbol] = 'up';
          else if (update.price < prev) flashes[update.symbol] = 'down';
        }
        if (update.price) prevPrices.current[update.symbol] = update.price;
      });

      // Set flash state
      if (Object.keys(flashes).length > 0) {
        setPriceFlash(flashes);
        setTimeout(() => setPriceFlash({}), 800);
      }

      // Update prices in dashboard data
      setData(prev => {
        if (!prev) return prev;
        const updatedPrices = prev.prices.map(p => {
          const update = prices.find((u: any) => u.symbol === p.symbol);
          if (update) {
            return {
              ...p,
              price: update.price ?? p.price,
              priceChange24h: update.priceChange24h ?? p.priceChange24h,
              priceChangePct24h: update.priceChangePct24h ?? p.priceChangePct24h,
              volume24h: update.volume24h ?? p.volume24h,
            };
          }
          return p;
        });
        return { ...prev, prices: updatedPrices, timestamp: new Date().toISOString() };
      });
    },
    onNews: (news: any[]) => {
      if (!news || !Array.isArray(news)) return;
      setData(prev => {
        if (!prev) return prev;
        // Prepend new news, deduplicate by id
        const existingIds = new Set(prev.latestNews.map((n: any) => n.id));
        const newArticles = news.filter((n: any) => !existingIds.has(n.id));
        return {
          ...prev,
          latestNews: [...newArticles, ...prev.latestNews].slice(0, 20),
        };
      });
    },
  });

  return { data, loading, error, refresh: fetchData, connected, priceFlash };
}
