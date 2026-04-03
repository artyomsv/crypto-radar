import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';
import { formatPrice, formatLargeNumber, formatPercent, getTrendBadgeColor } from '@/lib/utils';
import { Loader2, Filter, ArrowUpDown, RefreshCw } from 'lucide-react';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type {
  DashboardData,
  DerivativesOverview,
  WhaleMarketOverview,
  VolatilityMetric,
  WhaleAnalytics,
  SymbolDerivatives,
  MarketAnalysis,
  PriceData,
} from '@/types';

interface ScreenerRow {
  symbol: string;
  name: string;
  icon: string;
  price: number;
  change24h: number;
  rsi: number | null;
  fundingRate: number | null;
  openInterest: number | null;
  longPct: number | null;
  shortPct: number | null;
  whalePressure: number | null;
  whaleVolume1h: number | null;
  atrPct: number | null;
  trend: string;
  overallScore: number;
}

type SortKey = keyof Pick<
  ScreenerRow,
  'symbol' | 'price' | 'change24h' | 'rsi' | 'fundingRate' | 'openInterest' |
  'longPct' | 'whalePressure' | 'whaleVolume1h' | 'atrPct' | 'overallScore'
>;

interface Filters {
  rsiMax: string;
  rsiMin: string;
  fundingMin: string;
  trendFilter: string;
}

function buildRows(
  dashboard: DashboardData | null,
  derivatives: DerivativesOverview | null,
  whales: WhaleMarketOverview | null,
  volatility: VolatilityMetric[] | null,
): ScreenerRow[] {
  if (!dashboard) return [];

  const priceMap = new Map<string, PriceData>();
  for (const p of dashboard.prices) priceMap.set(p.symbol, p);

  const analysisMap = new Map<string, MarketAnalysis>();
  if (dashboard.marketOverview?.analyses) {
    for (const a of dashboard.marketOverview.analyses) analysisMap.set(a.symbol, a);
  }

  const derivMap = new Map<string, SymbolDerivatives>();
  if (derivatives?.symbolData) {
    for (const d of derivatives.symbolData) derivMap.set(d.symbol, d);
  }

  const whaleMap = new Map<string, WhaleAnalytics>();
  if (whales?.symbolAnalytics) {
    for (const w of whales.symbolAnalytics) whaleMap.set(w.symbol, w);
  }

  const volMap = new Map<string, VolatilityMetric>();
  if (volatility) {
    for (const v of volatility) volMap.set(v.symbol, v);
  }

  const symbols = [...priceMap.keys()];
  return symbols.map(symbol => {
    const price = priceMap.get(symbol);
    const analysis = analysisMap.get(symbol);
    const deriv = derivMap.get(symbol);
    const whale = whaleMap.get(symbol);
    const vol = volMap.get(symbol);

    return {
      symbol,
      name: SYMBOL_NAMES[symbol] || symbol.replace('USDT', ''),
      icon: SYMBOL_ICONS[symbol] || '?',
      price: price?.price ?? 0,
      change24h: price?.priceChangePct24h ?? 0,
      rsi: analysis?.technicalIndicators?.rsi14 ?? null,
      fundingRate: deriv?.fundingRate ?? null,
      openInterest: deriv?.openInterestUsd ?? null,
      longPct: deriv?.longPct ?? null,
      shortPct: deriv?.shortPct ?? null,
      whalePressure: whale?.whalePressure ?? null,
      whaleVolume1h: whale ? whale.buyVolumeUsd1h + whale.sellVolumeUsd1h : null,
      atrPct: vol?.atrPct ?? null,
      trend: analysis?.trendDirection ?? 'NEUTRAL',
      overallScore: analysis?.overallScore ?? 0,
    };
  });
}

function rsiColor(rsi: number | null): string {
  if (rsi == null) return 'text-text-secondary';
  if (rsi <= 30) return 'text-gain';
  if (rsi >= 70) return 'text-loss';
  return 'text-text-primary';
}

function fundingColor(rate: number | null): string {
  if (rate == null) return 'text-text-secondary';
  if (rate > 0.01) return 'text-gain';
  if (rate < -0.01) return 'text-loss';
  return 'text-text-primary';
}

function pressureColor(pressure: number | null): string {
  if (pressure == null) return 'text-text-secondary';
  if (pressure > 0.3) return 'text-gain';
  if (pressure < -0.3) return 'text-loss';
  return 'text-text-primary';
}

export function Screener() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [dashboard, setDashboard] = useState<DashboardData | null>(null);
  const [derivatives, setDerivatives] = useState<DerivativesOverview | null>(null);
  const [whales, setWhales] = useState<WhaleMarketOverview | null>(null);
  const [volatility, setVolatility] = useState<VolatilityMetric[] | null>(null);

  const [sortKey, setSortKey] = useState<SortKey>('overallScore');
  const [sortAsc, setSortAsc] = useState(false);
  const [filters, setFilters] = useState<Filters>({
    rsiMax: '',
    rsiMin: '',
    fundingMin: '',
    trendFilter: '',
  });
  const [showFilters, setShowFilters] = useState(false);

  const fetchAll = useCallback(async () => {
    const [d, deriv, w, v] = await Promise.all([
      api.getDashboard(),
      api.getDerivativesOverview(),
      api.getWhaleAnalytics(),
      api.getVolatilityMetrics(),
    ]);
    if (d) setDashboard(d);
    if (deriv) setDerivatives(deriv);
    if (w) setWhales(w);
    if (v) setVolatility(v);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 30_000);
    return () => clearInterval(interval);
  }, [fetchAll]);

  const rows = useMemo(
    () => buildRows(dashboard, derivatives, whales, volatility),
    [dashboard, derivatives, whales, volatility],
  );

  const filteredRows = useMemo(() => {
    let result = [...rows];
    const rsiMin = parseFloat(filters.rsiMin);
    const rsiMax = parseFloat(filters.rsiMax);
    const fundingMin = parseFloat(filters.fundingMin);

    if (!isNaN(rsiMin)) result = result.filter(r => r.rsi != null && r.rsi >= rsiMin);
    if (!isNaN(rsiMax)) result = result.filter(r => r.rsi != null && r.rsi <= rsiMax);
    if (!isNaN(fundingMin)) result = result.filter(r => r.fundingRate != null && r.fundingRate >= fundingMin);
    if (filters.trendFilter) result = result.filter(r => r.trend === filters.trendFilter);

    return result;
  }, [rows, filters]);

  const sortedRows = useMemo(() => {
    return [...filteredRows].sort((a, b) => {
      const av = a[sortKey] ?? -Infinity;
      const bv = b[sortKey] ?? -Infinity;
      if (typeof av === 'string' && typeof bv === 'string') {
        return sortAsc ? av.localeCompare(bv) : bv.localeCompare(av);
      }
      const diff = (av as number) - (bv as number);
      return sortAsc ? diff : -diff;
    });
  }, [filteredRows, sortKey, sortAsc]);

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortAsc(!sortAsc);
    } else {
      setSortKey(key);
      setSortAsc(false);
    }
  };

  const sortHeader = (label: string, key: SortKey, align = 'text-right') => (
    <th className={`py-2 px-2 ${align}`}>
      <button
        onClick={() => handleSort(key)}
        className="flex items-center gap-1 text-[11px] text-text-secondary hover:text-text-primary transition-colors"
      >
        {label}
        <ArrowUpDown className={`h-3 w-3 ${sortKey === key ? 'text-accent' : ''}`} />
      </button>
    </th>
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <Loader2 className="h-8 w-8 text-accent animate-spin" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Filter className="h-6 w-6 text-accent" />
          <h1 className="text-xl font-bold text-text-primary">Screener</h1>
          <span className="text-xs text-text-secondary">{sortedRows.length} coins</span>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setShowFilters(!showFilters)}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-xs rounded border transition-colors ${
              showFilters
                ? 'bg-accent/10 text-accent border-accent/30'
                : 'bg-surface-light text-text-secondary border-surface-border hover:border-accent/30'
            }`}
          >
            <Filter className="h-3.5 w-3.5" />
            Filters
          </button>
          <button
            onClick={fetchAll}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-text-secondary bg-surface-light border border-surface-border rounded hover:border-accent/30 transition-colors"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Refresh
          </button>
        </div>
      </div>

      {/* Filter Row */}
      {showFilters && (
        <div className="glass-card p-4 grid grid-cols-2 md:grid-cols-4 gap-3">
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">RSI Min</label>
            <input
              type="number"
              placeholder="e.g. 20"
              value={filters.rsiMin}
              onChange={e => setFilters(f => ({ ...f, rsiMin: e.target.value }))}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
            />
          </div>
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">RSI Max</label>
            <input
              type="number"
              placeholder="e.g. 30"
              value={filters.rsiMax}
              onChange={e => setFilters(f => ({ ...f, rsiMax: e.target.value }))}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
            />
          </div>
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">Funding Rate Min (%)</label>
            <input
              type="number"
              step="0.001"
              placeholder="e.g. 0.01"
              value={filters.fundingMin}
              onChange={e => setFilters(f => ({ ...f, fundingMin: e.target.value }))}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
            />
          </div>
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">Trend</label>
            <select
              value={filters.trendFilter}
              onChange={e => setFilters(f => ({ ...f, trendFilter: e.target.value }))}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs text-text-primary focus:outline-none focus:border-accent"
            >
              <option value="">All</option>
              <option value="STRONG_BULLISH">Strong Bullish</option>
              <option value="BULLISH">Bullish</option>
              <option value="NEUTRAL">Neutral</option>
              <option value="BEARISH">Bearish</option>
              <option value="STRONG_BEARISH">Strong Bearish</option>
            </select>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="glass-card overflow-x-auto">
        <table className="w-full min-w-[1100px]">
          <thead>
            <tr className="border-b border-surface-border">
              <th className="text-left py-2 px-3">
                <span className="text-[11px] text-text-secondary">Symbol</span>
              </th>
              {sortHeader('Price', 'price')}
              {sortHeader('24h %', 'change24h')}
              {sortHeader('RSI(14)', 'rsi')}
              {sortHeader('Funding', 'fundingRate')}
              {sortHeader('OI', 'openInterest')}
              {sortHeader('Long%', 'longPct')}
              {sortHeader('Whale Pr.', 'whalePressure')}
              {sortHeader('Whale Vol 1h', 'whaleVolume1h')}
              {sortHeader('ATR %', 'atrPct')}
              <th className="py-2 px-2 text-center">
                <span className="text-[11px] text-text-secondary">Trend</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {sortedRows.map(row => (
              <tr
                key={row.symbol}
                onClick={() => navigate(`/crypto/${row.symbol}`)}
                className="border-b border-surface-border/50 hover:bg-surface-light/30 cursor-pointer transition-colors"
              >
                {/* Symbol */}
                <td className="py-2.5 px-3">
                  <div className="flex items-center gap-2">
                    <span className="text-lg font-mono text-accent">{row.icon}</span>
                    <div>
                      <p className="text-sm font-medium text-text-primary">{row.name}</p>
                      <p className="text-[10px] text-text-secondary">{row.symbol.replace('USDT', '')}</p>
                    </div>
                  </div>
                </td>

                {/* Price */}
                <td className="py-2.5 px-2 text-right">
                  <span className="text-sm font-mono text-text-primary">{formatPrice(row.price)}</span>
                </td>

                {/* 24h Change */}
                <td className="py-2.5 px-2 text-right">
                  <span className={`text-sm font-mono font-medium ${row.change24h >= 0 ? 'text-gain' : 'text-loss'}`}>
                    {formatPercent(row.change24h)}
                  </span>
                </td>

                {/* RSI */}
                <td className="py-2.5 px-2 text-right">
                  <span className={`text-sm font-mono font-medium ${rsiColor(row.rsi)}`}>
                    {row.rsi != null ? row.rsi.toFixed(1) : '-'}
                  </span>
                </td>

                {/* Funding Rate */}
                <td className="py-2.5 px-2 text-right">
                  <span className={`text-sm font-mono ${fundingColor(row.fundingRate)}`}>
                    {row.fundingRate != null ? `${(row.fundingRate * 100).toFixed(4)}%` : '-'}
                  </span>
                </td>

                {/* Open Interest */}
                <td className="py-2.5 px-2 text-right">
                  <span className="text-sm font-mono text-text-primary">
                    {row.openInterest != null ? formatLargeNumber(row.openInterest) : '-'}
                  </span>
                </td>

                {/* Long % */}
                <td className="py-2.5 px-2 text-right">
                  {row.longPct != null ? (
                    <div className="flex items-center justify-end gap-1">
                      <span className="text-xs font-mono text-gain">{row.longPct.toFixed(1)}%</span>
                      <span className="text-[10px] text-text-secondary">/</span>
                      <span className="text-xs font-mono text-loss">{(row.shortPct ?? 0).toFixed(1)}%</span>
                    </div>
                  ) : (
                    <span className="text-sm text-text-secondary">-</span>
                  )}
                </td>

                {/* Whale Pressure */}
                <td className="py-2.5 px-2 text-right">
                  <span className={`text-sm font-mono font-medium ${pressureColor(row.whalePressure)}`}>
                    {row.whalePressure != null ? row.whalePressure.toFixed(2) : '-'}
                  </span>
                </td>

                {/* Whale Volume 1h */}
                <td className="py-2.5 px-2 text-right">
                  <span className="text-sm font-mono text-text-primary">
                    {row.whaleVolume1h != null ? formatLargeNumber(row.whaleVolume1h) : '-'}
                  </span>
                </td>

                {/* ATR % */}
                <td className="py-2.5 px-2 text-right">
                  <span className="text-sm font-mono text-text-primary">
                    {row.atrPct != null ? `${row.atrPct.toFixed(2)}%` : '-'}
                  </span>
                </td>

                {/* Trend */}
                <td className="py-2.5 px-2 text-center">
                  <span className={`text-[10px] font-medium px-2 py-0.5 rounded border ${getTrendBadgeColor(row.trend)}`}>
                    {row.trend.replace('_', ' ')}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Footer */}
      <div className="flex items-center justify-between text-[10px] text-text-secondary px-1">
        <span>Click any row to view details</span>
        <span>Auto-refreshes every 30s</span>
      </div>
    </div>
  );
}
