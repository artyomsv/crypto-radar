import { API_BASE } from './utils';
import type { DashboardData, CryptoDetail, WhaleTransaction, WhaleMarketOverview, WhaleDistribution, WhaleFlowSummary, DerivativesOverview, FundingRate, LiquidationEvent, PriceAlert, CorrelationMatrix, VolatilityMetric, OrderBookDepth, PortfolioPosition, MacroOverview, SignalOverview, TradingSignal, PerformanceReport, SignalOutcomeView, ExchangeAccount, WalletSnapshot, ExecutionPosition, ExecutionTrade, ExecutionEvent, WhyView, CreateAccountRequest, UpdateAccountRequest, TradeHistoryPage, SignalConfig, SignalConfigVersion, BacktestRun, BacktestRunDetail, ExecutionSettings, TelegramTestResult, OptionChainRow, OptionOpportunity, RealizedVolReading, EnrichedOptionOpportunity, HitRateBucket } from '@/types';

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

async function sendJson<T>(
  url: string,
  method: 'POST' | 'PATCH' | 'DELETE',
  body?: unknown
): Promise<{ data: T | null; error: string | null; status: number }> {
  try {
    const response = await fetch(`${API_BASE}${url}`, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await response.text();
    let parsed: unknown = null;
    if (text) {
      try {
        parsed = JSON.parse(text);
      } catch {
        // non-JSON body; keep raw text for diagnostic use below, parsed stays null
      }
    }
    if (!response.ok) {
      const errorMsg =
        parsed && typeof parsed === 'object' && 'error' in parsed
          ? String((parsed as { error: unknown }).error)
          : text || `HTTP ${response.status}`;
      return { data: null, status: response.status, error: errorMsg };
    }
    return { data: parsed as T, status: response.status, error: null };
  } catch (e) {
    return { data: null, status: 0, error: e instanceof Error ? e.message : 'network error' };
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
  execution: {
    listAccounts: () => fetchJson<ExchangeAccount[]>('/api/execution/accounts'),
    getAccount: (id: number) => fetchJson<ExchangeAccount>(`/api/execution/accounts/${id}`),
    createAccount: (req: CreateAccountRequest) =>
      sendJson<ExchangeAccount>('/api/execution/accounts', 'POST', req),
    patchAccount: (id: number, req: UpdateAccountRequest) =>
      sendJson<ExchangeAccount>(`/api/execution/accounts/${id}`, 'PATCH', req),
    deleteAccount: (id: number) =>
      sendJson<null>(`/api/execution/accounts/${id}`, 'DELETE'),
    getWallet: (id: number) => fetchJson<WalletSnapshot>(`/api/execution/accounts/${id}/wallet`),
    getPositions: (id: number) => fetchJson<ExecutionPosition[]>(`/api/execution/accounts/${id}/positions`),
    getTrades: (id: number, limit = 50) =>
      fetchJson<ExecutionTrade[]>(`/api/execution/accounts/${id}/trades?limit=${limit}`),
    getTradeHistory: (id: number, page = 0, pageSize = 25) =>
      fetchJson<TradeHistoryPage>(
        `/api/execution/accounts/${id}/trades/history?page=${page}&pageSize=${pageSize}`,
      ),
    getEvents: (id: number, limit = 100) =>
      fetchJson<ExecutionEvent[]>(`/api/execution/accounts/${id}/events?limit=${limit}`),
    getWhy: (accountId: number, tradeId: number) =>
      fetchJson<WhyView>(`/api/execution/accounts/${accountId}/trades/${tradeId}/why`),
    toggleKillSwitch: (id: number, enabled: boolean) =>
      sendJson<{ killSwitch: boolean }>(`/api/execution/accounts/${id}/kill-switch`, 'POST', { enabled }),
    closeAll: (id: number, confirm: string) =>
      sendJson<{ closedCount: number }>(`/api/execution/accounts/${id}/close-all`, 'POST', { confirm }),
    closeTrade: (accountId: number, tradeId: number) =>
      sendJson<ExecutionTrade>(`/api/execution/accounts/${accountId}/trades/${tradeId}/close`, 'POST'),
  },

  // Signal config + backtest
  getSignalConfig: () => fetchJson<SignalConfigVersion>('/api/signals/config'),
  listSignalConfigVersions: (limit = 50, offset = 0) =>
    fetchJson<SignalConfigVersion[]>(`/api/signals/config/versions?limit=${limit}&offset=${offset}`),
  getSignalConfigVersion: (id: number) =>
    fetchJson<SignalConfigVersion>(`/api/signals/config/versions/${id}`),
  saveSignalConfigVersion: async (
    config: SignalConfig,
    description: string,
    parentVersionId?: number,
  ): Promise<SignalConfigVersion> => {
    const res = await fetch(`${API_BASE}/api/signals/config/versions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ config, description, parentVersionId }),
    });
    if (!res.ok) {
      const err = (await res.json().catch(() => ({ error: `HTTP ${res.status}` }))) as { error?: string };
      throw new Error(err.error ?? `HTTP ${res.status}`);
    }
    return (await res.json()) as SignalConfigVersion;
  },
  activateSignalConfigVersion: async (id: number): Promise<SignalConfigVersion> => {
    const res = await fetch(`${API_BASE}/api/signals/config/versions/${id}/activate`, {
      method: 'POST',
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as SignalConfigVersion;
  },
  runBacktest: async (
    configVersionId: number,
    periodStart: string,
    periodEnd: string,
    tier = 1,
    alignmentFloor: number | null = null,
  ): Promise<BacktestRun> => {
    const body: Record<string, unknown> = { configVersionId, periodStart, periodEnd, tier };
    if (alignmentFloor !== null) body.alignmentFloor = alignmentFloor;
    const res = await fetch(`${API_BASE}/api/signals/backtest`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as BacktestRun;
  },
  listBacktestRuns: (configVersionId?: number, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (configVersionId !== undefined) params.set('configVersionId', String(configVersionId));
    return fetchJson<BacktestRun[]>(`/api/signals/backtest/runs?${params}`);
  },
  getBacktestRun: (id: number) =>
    fetchJson<BacktestRunDetail>(`/api/signals/backtest/runs/${id}`),

  // Options watchlist
  getOptionsChain: (underlying: string) =>
    fetchJson<OptionChainRow[]>(`/api/options/chain/${underlying}`),
  getOptionsOpportunities: (limit = 50, openOnly = false) =>
    fetchJson<OptionOpportunity[]>(`/api/options/opportunities?limit=${limit}&openOnly=${openOnly}`),
  getOptionsOpportunitiesEnriched: (limit = 50, openOnly = false, includeStale = false) =>
    fetchJson<EnrichedOptionOpportunity[]>(
      `/api/options/opportunities/enriched?limit=${limit}&openOnly=${openOnly}&includeStale=${includeStale}`,
    ),
  getOptionsOpportunity: (id: number) =>
    fetchJson<OptionOpportunity>(`/api/options/opportunities/${id}`),
  getOptionsRealizedVol: (underlying: string, days = 14) =>
    fetchJson<RealizedVolReading>(`/api/options/realized-vol/${underlying}?days=${days}`),
  getOptionsHitRate: () =>
    fetchJson<HitRateBucket[]>('/api/options/stats/hit-rate'),

  // Execution gates (runtime-tunable on trade-execution-service)
  getExecutionSettings: () => fetchJson<ExecutionSettings>('/api/execution/settings'),
  updateExecutionSettings: async (next: ExecutionSettings): Promise<ExecutionSettings> => {
    const res = await fetch(`${API_BASE}/api/execution/settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(next),
    });
    if (!res.ok) {
      const err = (await res.json().catch(() => ({ error: `HTTP ${res.status}` }))) as { error?: string };
      throw new Error(err.error ?? `HTTP ${res.status}`);
    }
    return (await res.json()) as ExecutionSettings;
  },
  testTelegramNotification: async (
    botToken: string | null,
    chatId: string | null,
  ): Promise<TelegramTestResult> => {
    const res = await fetch(`${API_BASE}/api/execution/settings/telegram/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ botToken, chatId }),
    });
    if (!res.ok) {
      return { ok: false, error: `HTTP ${res.status}` };
    }
    return (await res.json()) as TelegramTestResult;
  },
};
