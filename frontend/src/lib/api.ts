import { API_BASE } from './utils';
import type { DashboardData, CryptoDetail, WhaleTransaction, WhaleMarketOverview, WhaleFlowSummary, DerivativesOverview, FundingRate, LiquidationEvent } from '@/types';

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
  fetchArticle: (url: string) =>
    fetchJson<{ title: string; body: string; imageUrl: string; images: string[]; paragraphCount: number }>(
      `/api/news/fetch?url=${encodeURIComponent(url)}`
    ),
  getWhaleTransactions: (symbol?: string, limit = 100, period = '1d') => {
    const params = new URLSearchParams({ limit: String(limit), period });
    if (symbol) params.set('symbol', symbol);
    return fetchJson<WhaleTransaction[]>(`/api/whales/transactions?${params}`);
  },
  getWhaleAnalytics: () => fetchJson<WhaleMarketOverview>('/api/whales/analytics'),
  getWhaleFlow: (symbol: string, window = '1h') =>
    fetchJson<WhaleFlowSummary>(`/api/whales/flow/${symbol}?window=${window}`),
  getConfigCryptos: () => fetchJson<any[]>('/api/market/config/cryptos'),
  addCrypto: async (symbol: string, name: string) => {
    try {
      const res = await fetch(`${API_BASE}/api/market/config/cryptos`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ symbol, name })
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to add crypto:', error);
      return { error: 'Request failed' };
    }
  },
  toggleCrypto: async (symbol: string, isActive: boolean) => {
    try {
      const res = await fetch(`${API_BASE}/api/market/config/cryptos/${symbol}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ isActive })
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to toggle crypto:', error);
      return { error: 'Request failed' };
    }
  },
  removeCrypto: async (symbol: string, deleteData = false) => {
    try {
      const res = await fetch(`${API_BASE}/api/market/config/cryptos/${symbol}?deleteData=${deleteData}`, {
        method: 'DELETE'
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to remove crypto:', error);
      return { error: 'Request failed' };
    }
  },
  getDerivativesOverview: () => fetchJson<DerivativesOverview>('/api/derivatives/overview'),
  getDerivativesFundingRates: () => fetchJson<FundingRate[]>('/api/derivatives/funding-rates'),
  getLiquidations: (limit = 50) => fetchJson<LiquidationEvent[]>(`/api/derivatives/liquidations?limit=${limit}`),
  searchSymbols: (q: string) => fetchJson<any[]>(`/api/market/config/search?q=${encodeURIComponent(q)}`),
  getBackfillConfig: () => fetchJson<any[]>('/api/market/config/backfill'),
  updateBackfillDepth: async (interval: string, depthDays: number) => {
    try {
      const res = await fetch(`${API_BASE}/api/market/config/backfill/${interval}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ depthDays })
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to update backfill depth:', error);
      return { error: 'Request failed' };
    }
  },
};
