import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { Loader2, Activity, TrendingUp, ArrowUpDown } from 'lucide-react';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { VolatilityMetric } from '@/types';

function volatilityColor(value: number | null): string {
  if (value == null) return 'text-text-secondary';
  if (value >= 8) return 'text-red-400';
  if (value >= 5) return 'text-orange-400';
  if (value >= 3) return 'text-yellow-400';
  if (value >= 1.5) return 'text-emerald-400';
  return 'text-emerald-300';
}

function volatilityBg(value: number | null): string {
  if (value == null) return '';
  if (value >= 8) return 'bg-red-500/10';
  if (value >= 5) return 'bg-orange-500/10';
  if (value >= 3) return 'bg-yellow-500/10';
  return '';
}

function volatilityLabel(value: number | null): string {
  if (value == null) return '-';
  if (value >= 8) return 'Extreme';
  if (value >= 5) return 'High';
  if (value >= 3) return 'Moderate';
  if (value >= 1.5) return 'Low';
  return 'Very Low';
}

function formatMetric(value: number | null): string {
  if (value == null) return '-';
  return value.toFixed(2) + '%';
}

type SortField = 'rank' | 'atrPct' | 'bollingerWidth' | 'range24hPct';

export function VolatilityDashboard() {
  const [metrics, setMetrics] = useState<VolatilityMetric[]>([]);
  const [loading, setLoading] = useState(true);
  const [sortField, setSortField] = useState<SortField>('rank');
  const [sortAsc, setSortAsc] = useState(true);

  const fetchData = useCallback(async () => {
    const result = await api.getVolatilityMetrics();
    if (result) setMetrics(result);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 60000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortAsc(!sortAsc);
    } else {
      setSortField(field);
      setSortAsc(field === 'rank');
    }
  };

  const sortedMetrics = [...metrics].sort((a, b) => {
    let cmp = 0;
    switch (sortField) {
      case 'rank':
        cmp = a.volatilityRank - b.volatilityRank;
        break;
      case 'atrPct':
        cmp = (a.atrPct ?? 0) - (b.atrPct ?? 0);
        break;
      case 'bollingerWidth':
        cmp = (a.bollingerWidth ?? 0) - (b.bollingerWidth ?? 0);
        break;
      case 'range24hPct':
        cmp = (a.range24hPct ?? 0) - (b.range24hPct ?? 0);
        break;
    }
    return sortAsc ? cmp : -cmp;
  });

  if (loading) {
    return (
      <div className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Activity className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Volatility Rankings</h2>
        </div>
        <div className="flex items-center justify-center h-48">
          <Loader2 className="h-6 w-6 text-accent animate-spin" />
        </div>
      </div>
    );
  }

  if (metrics.length === 0) {
    return (
      <div className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Activity className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Volatility Rankings</h2>
        </div>
        <p className="text-text-secondary text-sm text-center py-8">
          No volatility data available.
        </p>
      </div>
    );
  }

  const sortHeader = (label: string, field: SortField) => (
    <button
      onClick={() => handleSort(field)}
      className="flex items-center gap-1 text-[11px] text-text-secondary hover:text-text-primary transition-colors"
    >
      {label}
      <ArrowUpDown className={`h-3 w-3 ${sortField === field ? 'text-accent' : ''}`} />
    </button>
  );

  return (
    <div className="glass-card p-5">
      <div className="flex items-center gap-2 mb-4">
        <Activity className="h-5 w-5 text-accent" />
        <h2 className="text-lg font-semibold text-text-primary">Volatility Rankings</h2>
        <span className="text-xs text-text-secondary">Sorted by composite score</span>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-surface-border">
              <th className="text-left py-2 px-2">
                {sortHeader('#', 'rank')}
              </th>
              <th className="text-left py-2 px-2">
                <span className="text-[11px] text-text-secondary">Symbol</span>
              </th>
              <th className="text-right py-2 px-2">
                {sortHeader('ATR(14) %', 'atrPct')}
              </th>
              <th className="text-right py-2 px-2">
                {sortHeader('BB Width %', 'bollingerWidth')}
              </th>
              <th className="text-right py-2 px-2">
                {sortHeader('24h Range %', 'range24hPct')}
              </th>
              <th className="text-center py-2 px-2">
                <span className="text-[11px] text-text-secondary">Level</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {sortedMetrics.map(metric => {
              const name = SYMBOL_NAMES[metric.symbol] || metric.symbol;
              const icon = SYMBOL_ICONS[metric.symbol] || '?';
              const avgVol = computeAvgVolatility(metric);

              return (
                <tr
                  key={metric.symbol}
                  className={`border-b border-surface-border/50 hover:bg-surface-light/30 transition-colors ${volatilityBg(avgVol)}`}
                >
                  <td className="py-2.5 px-2">
                    <span className={`text-sm font-bold font-mono ${
                      metric.volatilityRank <= 3 ? 'text-red-400' :
                      metric.volatilityRank <= 6 ? 'text-orange-400' : 'text-text-secondary'
                    }`}>
                      {metric.volatilityRank}
                    </span>
                  </td>
                  <td className="py-2.5 px-2">
                    <div className="flex items-center gap-2">
                      <span className="text-xl font-mono text-accent">{icon}</span>
                      <div>
                        <p className="text-sm font-medium text-text-primary">{name}</p>
                        <p className="text-[10px] text-text-secondary">{metric.symbol.replace('USDT', '')}</p>
                      </div>
                    </div>
                  </td>
                  <td className="py-2.5 px-2 text-right">
                    <span className={`text-sm font-mono font-medium ${volatilityColor(metric.atrPct)}`}>
                      {formatMetric(metric.atrPct)}
                    </span>
                  </td>
                  <td className="py-2.5 px-2 text-right">
                    <span className={`text-sm font-mono font-medium ${volatilityColor(metric.bollingerWidth)}`}>
                      {formatMetric(metric.bollingerWidth)}
                    </span>
                  </td>
                  <td className="py-2.5 px-2 text-right">
                    <span className={`text-sm font-mono font-medium ${volatilityColor(metric.range24hPct)}`}>
                      {formatMetric(metric.range24hPct)}
                    </span>
                  </td>
                  <td className="py-2.5 px-2 text-center">
                    <span className={`text-[10px] font-medium px-2 py-0.5 rounded ${
                      volatilityLabel(avgVol) === 'Extreme' ? 'bg-red-500/20 text-red-400' :
                      volatilityLabel(avgVol) === 'High' ? 'bg-orange-500/20 text-orange-400' :
                      volatilityLabel(avgVol) === 'Moderate' ? 'bg-yellow-500/20 text-yellow-400' :
                      'bg-emerald-500/20 text-emerald-400'
                    }`}>
                      {volatilityLabel(avgVol)}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Summary bar */}
      <div className="flex items-center justify-between mt-4 pt-3 border-t border-surface-border">
        <div className="flex items-center gap-4 text-xs text-text-secondary">
          <div className="flex items-center gap-1">
            <TrendingUp className="h-3 w-3 text-red-400" />
            <span>Most volatile: {sortedMetrics.length > 0 ? SYMBOL_NAMES[sortedMetrics[0]?.symbol] || sortedMetrics[0]?.symbol : '-'}</span>
          </div>
          <div className="flex items-center gap-1">
            <TrendingUp className="h-3 w-3 text-emerald-400" />
            <span>Least volatile: {sortedMetrics.length > 0 ? SYMBOL_NAMES[sortedMetrics[sortedMetrics.length - 1]?.symbol] || sortedMetrics[sortedMetrics.length - 1]?.symbol : '-'}</span>
          </div>
        </div>
        <span className="text-[10px] text-text-secondary">{metrics.length} symbols tracked</span>
      </div>
    </div>
  );
}

function computeAvgVolatility(metric: VolatilityMetric): number | null {
  let sum = 0;
  let count = 0;
  if (metric.atrPct != null) { sum += metric.atrPct; count++; }
  if (metric.bollingerWidth != null) { sum += metric.bollingerWidth; count++; }
  if (metric.range24hPct != null) { sum += metric.range24hPct; count++; }
  return count > 0 ? sum / count : null;
}
