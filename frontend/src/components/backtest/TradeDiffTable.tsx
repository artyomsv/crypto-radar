import { useState } from 'react';
import type { BacktestTrade } from '@/types';
import { cn } from '@/lib/utils';
import { ArrowUpRight, ArrowDownRight } from 'lucide-react';

type DiffFilter = 'all' | 'diffs' | 'wins' | 'losses';

interface TradeDiffTableProps {
  trades: BacktestTrade[];
}

const FILTER_LABELS: Record<DiffFilter, string> = {
  all: 'Show all',
  diffs: 'Diffs only',
  wins: 'Wins',
  losses: 'Losses',
};

function formatDateShort(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function formatR(r: number | null): string {
  if (r === null) return '—';
  return `${r >= 0 ? '+' : ''}${r.toFixed(2)}R`;
}

function rColor(r: number | null): string {
  if (r === null) return 'text-text-secondary';
  if (r > 0) return 'text-gain';
  if (r < 0) return 'text-loss';
  return 'text-text-secondary';
}

function SignalBadge({ signal, alignment }: { signal: string; alignment: number }) {
  const isLong = signal === 'STRONG_BUY' || signal === 'BUY';
  const isShort = signal === 'STRONG_SELL' || signal === 'SELL';
  return (
    <span className="inline-flex items-center gap-0.5">
      <span className={cn(
        'text-[10px] font-semibold px-1 py-0.5 rounded border',
        isLong ? 'bg-gain/10 text-gain border-gain/30' :
          isShort ? 'bg-loss/10 text-loss border-loss/30' :
            'bg-muted/10 text-muted border-muted/30'
      )}>
        {signal.replace('_', ' ')}
      </span>
      <span className="text-[10px] text-muted font-mono ml-0.5">{alignment}</span>
    </span>
  );
}

function applyFilter(trades: BacktestTrade[], filter: DiffFilter): BacktestTrade[] {
  switch (filter) {
    case 'diffs': return trades.filter(t => t.originalSignal !== t.backtestSignal);
    case 'wins': return trades.filter(t => (t.realizedRMultiple ?? 0) > 0);
    case 'losses': return trades.filter(t => (t.realizedRMultiple ?? 0) < 0);
    default: return trades;
  }
}

export function TradeDiffTable({ trades }: TradeDiffTableProps) {
  const [filter, setFilter] = useState<DiffFilter>('all');

  const visible = applyFilter(trades, filter);
  const diffCount = trades.filter(t => t.originalSignal !== t.backtestSignal).length;

  return (
    <div className="glass-card overflow-hidden">
      <div className="flex items-center justify-between p-4 border-b border-white/5">
        <h3 className="text-sm font-semibold text-text-primary">
          Trade Breakdown
          {diffCount > 0 && (
            <span className="ml-2 text-[10px] text-accent font-normal">{diffCount} signal diffs</span>
          )}
        </h3>
        <div className="flex items-center gap-1">
          {(Object.keys(FILTER_LABELS) as DiffFilter[]).map(f => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={cn(
                'px-2 py-0.5 rounded text-[10px] font-medium transition-colors',
                filter === f
                  ? 'bg-accent text-white'
                  : 'text-text-secondary hover:text-accent'
              )}
            >
              {FILTER_LABELS[f]}
            </button>
          ))}
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-text-secondary text-[10px] uppercase tracking-wide border-b border-white/5">
              <th className="text-left font-normal py-2 pl-3 pr-2">Time</th>
              <th className="text-left font-normal px-2">Symbol</th>
              <th className="text-left font-normal px-2">Side</th>
              <th className="text-left font-normal px-2">Original</th>
              <th className="text-left font-normal px-2">Backtest</th>
              <th className="text-right font-normal px-2">Realized R</th>
              <th className="text-right font-normal pr-3 pl-2">Contrib</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 && (
              <tr>
                <td colSpan={7} className="py-8 text-center text-sm text-text-secondary">
                  No trades match this filter.
                </td>
              </tr>
            )}
            {visible.map(trade => {
              const hasDiff = trade.originalSignal !== trade.backtestSignal;
              const isLong = trade.direction === 'LONG';
              const DirectionIcon = isLong ? ArrowUpRight : ArrowDownRight;
              return (
                <tr
                  key={trade.id}
                  className={cn(
                    'border-b border-white/5 last:border-0 transition-colors',
                    hasDiff ? 'bg-accent/5 hover:bg-accent/10' : 'hover:bg-white/[0.02]'
                  )}
                >
                  <td className="py-2 pl-3 pr-2 text-xs text-text-secondary whitespace-nowrap">
                    {formatDateShort(trade.outcomeFiredAt)}
                  </td>
                  <td className="py-2 px-2 text-sm font-semibold text-text-primary">
                    {trade.symbol.replace(/USDT$/, '')}
                  </td>
                  <td className="py-2 px-2">
                    <span className={cn('inline-flex items-center gap-0.5 text-xs font-semibold', isLong ? 'text-gain' : 'text-loss')}>
                      <DirectionIcon className="h-3 w-3" />
                      {trade.direction}
                    </span>
                  </td>
                  <td className="py-2 px-2">
                    <SignalBadge signal={trade.originalSignal} alignment={trade.originalAlignment} />
                  </td>
                  <td className="py-2 px-2">
                    <SignalBadge signal={trade.backtestSignal} alignment={trade.backtestAlignment} />
                  </td>
                  <td className={cn('py-2 px-2 text-xs text-right font-semibold whitespace-nowrap', rColor(trade.realizedRMultiple))}>
                    {formatR(trade.realizedRMultiple)}
                  </td>
                  <td className={cn('py-2 pr-3 pl-2 text-xs text-right font-semibold whitespace-nowrap', rColor(trade.contributedR))}>
                    {formatR(trade.contributedR)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
