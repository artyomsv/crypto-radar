import { API_BASE } from './utils';
import type { DashboardData, CryptoDetail, WhaleTransaction, WhaleMarketOverview, WhaleFlowSummary } from '@/types';

async function fetchJson<T>(url: string): Promise<T | null> {
  try {
    const response = await fetch(`${API_BASE}${url}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error(`API error: ${url}`, error);
    return null;
  }
}

export const api = {
  getDashboard: () => fetchJson<DashboardData>('/api/dashboard'),
  getCryptoDetail: (symbol: string) => fetchJson<CryptoDetail>(`/api/dashboard/${symbol}`),
  getPrices: () => fetchJson<any[]>('/api/market/prices'),
  getCandles: (symbol: string, interval = '1h', limit = 100) =>
    fetchJson<any[]>(`/api/market/candles/${symbol}?interval=${interval}&limit=${limit}`),
  getMarketOverview: () => fetchJson<any>('/api/analytics/market-overview'),
  getAnalysis: (symbol: string) => fetchJson<any>(`/api/analytics/${symbol}`),
  getLatestNews: (limit = 20) => fetchJson<any[]>(`/api/news/latest?limit=${limit}`),
  getSentiment: (symbol: string) => fetchJson<any>(`/api/news/sentiment/${symbol}`),
  getWhaleTransactions: (symbol?: string, limit = 100, period = '1d') => {
    const params = new URLSearchParams({ limit: String(limit), period });
    if (symbol) params.set('symbol', symbol);
    return fetchJson<WhaleTransaction[]>(`/api/whales/transactions?${params}`);
  },
  getWhaleAnalytics: () => fetchJson<WhaleMarketOverview>('/api/whales/analytics'),
  getWhaleFlow: (symbol: string, window = '1h') =>
    fetchJson<WhaleFlowSummary>(`/api/whales/flow/${symbol}?window=${window}`),
};
