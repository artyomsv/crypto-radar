import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { Loader2, Grid3X3 } from 'lucide-react';
import { SYMBOL_NAMES } from '@/types';
import type { CorrelationMatrix as CorrelationMatrixType } from '@/types';

const PERIOD_OPTIONS = [
  { label: '7d', days: 7 },
  { label: '30d', days: 30 },
  { label: '90d', days: 90 },
];

function correlationColor(value: number): string {
  // -1 (red) to 0 (gray) to +1 (green)
  if (value >= 0.8) return 'bg-emerald-500/80 text-white';
  if (value >= 0.5) return 'bg-emerald-500/50 text-emerald-100';
  if (value >= 0.2) return 'bg-emerald-500/25 text-emerald-200';
  if (value > -0.2) return 'bg-slate-600/40 text-slate-300';
  if (value > -0.5) return 'bg-red-500/25 text-red-200';
  if (value > -0.8) return 'bg-red-500/50 text-red-100';
  return 'bg-red-500/80 text-white';
}

function shortSymbol(symbol: string): string {
  return symbol.replace('USDT', '');
}

export function CorrelationMatrixPanel() {
  const [data, setData] = useState<CorrelationMatrixType | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedDays, setSelectedDays] = useState(30);
  const [hoveredCell, setHoveredCell] = useState<{ row: string; col: string; value: number } | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    const result = await api.getCorrelationMatrix('1h', selectedDays);
    if (result) setData(result);
    setLoading(false);
  }, [selectedDays]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  if (loading) {
    return (
      <div className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Grid3X3 className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Correlation Matrix</h2>
        </div>
        <div className="flex items-center justify-center h-48">
          <Loader2 className="h-6 w-6 text-accent animate-spin" />
        </div>
      </div>
    );
  }

  if (!data || !data.matrix || Object.keys(data.matrix).length === 0) {
    return (
      <div className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Grid3X3 className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Correlation Matrix</h2>
        </div>
        <p className="text-text-secondary text-sm text-center py-8">
          No correlation data available. Ensure candle data has been collected.
        </p>
      </div>
    );
  }

  const symbols = Object.keys(data.matrix);

  return (
    <div className="glass-card p-5">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <Grid3X3 className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Correlation Matrix</h2>
          <span className="text-xs text-text-secondary">Pearson r, daily returns</span>
        </div>
        <div className="flex items-center gap-3">
          {hoveredCell && (
            <span className="text-xs text-text-secondary whitespace-nowrap">
              <span className="text-text-primary font-medium">{SYMBOL_NAMES[hoveredCell.row] || shortSymbol(hoveredCell.row)}</span>
              {' \u2194 '}
              <span className="text-text-primary font-medium">{SYMBOL_NAMES[hoveredCell.col] || shortSymbol(hoveredCell.col)}</span>
              {': '}
              <span className={`font-mono font-bold ${
                hoveredCell.value >= 0.5 ? 'text-gain' :
                hoveredCell.value <= -0.5 ? 'text-loss' : 'text-text-primary'
              }`}>
                {hoveredCell.value.toFixed(4)}
              </span>
            </span>
          )}
          <div className="flex gap-1">
            {PERIOD_OPTIONS.map(opt => (
              <button
                key={opt.days}
                onClick={() => setSelectedDays(opt.days)}
                className={`px-3 py-1 text-xs rounded-md transition-colors ${
                  selectedDays === opt.days
                    ? 'bg-accent text-white'
                    : 'bg-surface-light text-text-secondary hover:text-text-primary'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Matrix grid */}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse">
          <thead>
            <tr>
              <th className="p-1 text-[10px] text-text-secondary font-normal" />
              {symbols.map(s => (
                <th key={s} className="p-1 text-[10px] text-text-secondary font-medium text-center min-w-[42px]">
                  {shortSymbol(s)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {symbols.map(rowSymbol => (
              <tr key={rowSymbol}>
                <td className="p-1 text-[10px] text-text-secondary font-medium text-right pr-2 whitespace-nowrap">
                  {shortSymbol(rowSymbol)}
                </td>
                {symbols.map(colSymbol => {
                  const value = data.matrix[rowSymbol]?.[colSymbol] ?? 0;
                  const isDiagonal = rowSymbol === colSymbol;
                  return (
                    <td
                      key={colSymbol}
                      className={`p-0.5 text-center cursor-pointer transition-opacity ${
                        isDiagonal ? 'opacity-50' : ''
                      }`}
                      onMouseEnter={() => setHoveredCell({ row: rowSymbol, col: colSymbol, value })}
                      onMouseLeave={() => setHoveredCell(null)}
                    >
                      <div className={`rounded px-1 py-1 text-[10px] font-mono font-medium ${correlationColor(value)}`}>
                        {value.toFixed(2)}
                      </div>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Legend */}
      <div className="flex items-center justify-center gap-2 mt-4 text-[10px] text-text-secondary">
        <div className="flex items-center gap-1">
          <div className="w-3 h-3 rounded bg-red-500/80" />
          <span>-1.0</span>
        </div>
        <div className="flex items-center gap-1">
          <div className="w-3 h-3 rounded bg-red-500/25" />
          <span>-0.5</span>
        </div>
        <div className="flex items-center gap-1">
          <div className="w-3 h-3 rounded bg-slate-600/40" />
          <span>0</span>
        </div>
        <div className="flex items-center gap-1">
          <div className="w-3 h-3 rounded bg-emerald-500/25" />
          <span>+0.5</span>
        </div>
        <div className="flex items-center gap-1">
          <div className="w-3 h-3 rounded bg-emerald-500/80" />
          <span>+1.0</span>
        </div>
      </div>
    </div>
  );
}
