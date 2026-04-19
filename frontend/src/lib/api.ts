import { API_BASE } from './utils';
import type { DashboardData, CryptoDetail, WhaleTransaction, WhaleMarketOverview, WhaleDistribution, WhaleFlowSummary, DerivativesOverview, FundingRate, LiquidationEvent, PriceAlert, CorrelationMatrix, VolatilityMetric, OrderBookDepth, PortfolioPosition, MacroOverview, SignalOverview, TradingSignal, PerformanceReport, SignalOutcomeView } from '@/types';

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
  getWhaleDistribution: (window = '1h') =>
    fetchJson<WhaleDistribution>(`/api/whales/distribution?window=${window}`),
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
  getLiquidationMap: (symbol: string) => fetchJson<any>(`/api/derivatives/liquidation-map/${symbol}`),
  searchSymbols: (q: string) => fetchJson<any[]>(`/api/market/config/search?q=${encodeURIComponent(q)}`),
  getBackfillConfig: () => fetchJson<any[]>('/api/market/config/backfill'),
  getAlerts: () => fetchJson<PriceAlert[]>('/api/alerts'),
  createAlert: async (symbol: string, condition: string, targetPrice: number, note?: string) => {
    try {
      const res = await fetch(`${API_BASE}/api/alerts`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ symbol, condition, targetPrice, note: note || null })
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to create alert:', error);
      return { error: 'Request failed' };
    }
  },
  deleteAlert: async (id: number) => {
    try {
      const res = await fetch(`${API_BASE}/api/alerts/${id}`, { method: 'DELETE' });
      return await res.json();
    } catch (error) {
      console.error('Failed to delete alert:', error);
      return { error: 'Request failed' };
    }
  },
  getCorrelationMatrix: (interval = '1h', days = 30) =>
    fetchJson<CorrelationMatrix>(`/api/analytics/correlation?interval=${interval}&days=${days}`),
  getVolatilityMetrics: () => fetchJson<VolatilityMetric[]>('/api/analytics/volatility'),
  getOrderBookDepth: (symbol: string) => fetchJson<OrderBookDepth>(`/api/market/depth/${symbol}`),
  getPortfolio: () => fetchJson<PortfolioPosition[]>('/api/portfolio'),
  addPosition: async (pos: { symbol: string; entryPrice: number; quantity: number; side: string; note?: string }) => {
    try {
      const res = await fetch(`${API_BASE}/api/portfolio`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(pos)
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to add position:', error);
      return { error: 'Request failed' };
    }
  },
  closePosition: async (id: number, closePrice: number) => {
    try {
      const res = await fetch(`${API_BASE}/api/portfolio/${id}/close`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ closePrice })
      });
      return await res.json();
    } catch (error) {
      console.error('Failed to close position:', error);
      return { error: 'Request failed' };
    }
  },
  deletePosition: async (id: number) => {
    try {
      const res = await fetch(`${API_BASE}/api/portfolio/${id}`, { method: 'DELETE' });
      return await res.json();
    } catch (error) {
      console.error('Failed to delete position:', error);
      return { error: 'Request failed' };
    }
  },
  getSignalOverview: () => fetchJson<SignalOverview>('/api/signals/overview'),
  getSignalMetrics: (periodDays = 30) =>
    fetchJson<PerformanceReport>(`/api/signals/metrics?periodDays=${periodDays}`),
  getSignalOutcomes: (symbol?: string, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (symbol) params.set('symbol', symbol);
    return fetchJson<SignalOutcomeView[]>(`/api/signals/outcomes?${params}`);
  },
  getSignalForSymbol: (symbol: string) => fetchJson<TradingSignal>(`/api/signals/${symbol}`),
  getSignalRawData: (symbol: string) => fetchJson<Record<string, unknown>>(`/api/signals/${symbol}/raw-data`),
  requestAiAnalysis: async (symbol: string) => {
    try {
      const res = await fetch(`${API_BASE}/api/signals/${symbol}/ai-analysis`, { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.json() as { symbol: string; analysis: string; timestamp: string };
    } catch (error) {
      console.error('AI analysis request failed:', error);
      return null;
    }
  },
  getMacroOverview: () => fetchJson<MacroOverview>('/api/analytics/macro'),
  getExportUrl: (symbol: string, interval: string, from?: string, to?: string) => {
    let url = `${API_BASE}/api/market/export/${symbol}?interval=${interval}`;
    if (from) url += `&from=${from}`;
    if (to) url += `&to=${to}`;
    return url;
  },
  getWhaleExportUrl: (period: string) =>
    `${API_BASE}/api/whales/export?period=${period}`,
  getLiquidationExportUrl: () =>
    `${API_BASE}/api/derivatives/export/liquidations`,
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
