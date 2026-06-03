import type { BacktestRun } from '@/types';
import { cn } from '@/lib/utils';
import { Clock, TrendingUp, TrendingDown } from 'lucide-react';

interface RunHistoryListProps {
  runs: BacktestRun[];
  activeRunId: number | null;
  onSelect: (run: BacktestRun) => void;
}

function formatDateCompact(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function formatR(r: number): string {
  return `${r >= 0 ? '+' : ''}${r.toFixed(2)}R`;
}

function formatPeriod(start: string, end: string): string {
  const s = new Date(start);
  const e = new Date(end);
  const days = Math.round((e.getTime() - s.getTime()) / 86_400_000);
  return `${days}d`;
}

export function RunHistoryList({ runs, activeRunId, onSelect }: RunHistoryListProps) {
  if (runs.length === 0) {
    return (
      <div className="glass-card p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-3">Run History</h3>
        <p className="text-xs text-text-secondary">No runs for this version yet.</p>
      </div>
    );
  }

  return (
    <div className="glass-card overflow-hidden">
      <div className="p-4 border-b border-white/5">
        <h3 className="text-sm font-semibold text-text-primary">Run History</h3>
        <p className="text-[10px] text-muted mt-0.5">{runs.length} run{runs.length !== 1 ? 's' : ''}</p>
      </div>
      <div className="divide-y divide-white/5">
        {runs.map(run => {
          const isActive = run.id === activeRunId;
          const isPositive = run.totalR >= 0;
          const Icon = isPositive ? TrendingUp : TrendingDown;
          return (
            <button
              key={run.id}
              onClick={() => onSelect(run)}
              className={cn(
                'w-full text-left p-3 transition-colors hover:bg-white/[0.04]',
                isActive && 'bg-accent/10 border-l-2 border-l-accent'
              )}
            >
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs font-semibold text-text-primary flex items-center gap-1">
                  <Icon className={cn('h-3 w-3', isPositive ? 'text-gain' : 'text-loss')} />
                  {formatR(run.totalR)}
                </span>
                <span className="text-[10px] text-muted">{formatPeriod(run.periodStart, run.periodEnd)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-text-secondary">{run.tradeCount} trades</span>
                <span className="flex items-center gap-0.5 text-[10px] text-muted">
                  <Clock className="h-2.5 w-2.5" />
                  {formatDateCompact(run.createdAt)}
                </span>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
