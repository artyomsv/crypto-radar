import type { BacktestRun } from '@/types';
import { cn } from '@/lib/utils';
import { TrendingUp, TrendingDown, Clock } from 'lucide-react';

interface BacktestSummaryCardProps {
  run: BacktestRun;
}

function DeltaBadge({ delta }: { delta: number }) {
  const isPositive = delta > 0;
  const Icon = isPositive ? TrendingUp : TrendingDown;
  return (
    <span className={cn('inline-flex items-center gap-0.5 text-[10px] font-semibold', isPositive ? 'text-gain' : 'text-loss')}>
      <Icon className="h-2.5 w-2.5" />
      {isPositive ? '+' : ''}{delta.toFixed(2)}
    </span>
  );
}

function MetricTile({ label, value, delta, subtext }: { label: string; value: string; delta?: number; subtext?: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] text-text-secondary uppercase tracking-wide">{label}</span>
      <div className="flex items-baseline gap-1.5">
        <span className="text-lg font-bold text-text-primary">{value}</span>
        {delta !== undefined && <DeltaBadge delta={delta} />}
      </div>
      {subtext && <span className="text-[10px] text-muted">{subtext}</span>}
    </div>
  );
}

function formatR(r: number): string {
  return `${r >= 0 ? '+' : ''}${r.toFixed(2)}R`;
}

export function BacktestSummaryCard({ run }: BacktestSummaryCardProps) {
  const winRate = run.tradeCount > 0 ? (run.winCount / run.tradeCount) * 100 : 0;
  const originalWinRate = run.originalTradeCount > 0
    ? ((run.winCount / run.tradeCount) * 100)
    : 0;
  const tradeCountDelta = run.tradeCount - run.originalTradeCount;
  const totalRDelta = run.totalR - run.originalTotalR;
  const durationSec = Math.round(run.durationMs / 1000);

  return (
    <div className="glass-card p-4">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-text-primary">Backtest Results</h3>
        <span className="flex items-center gap-1 text-[10px] text-muted">
          <Clock className="h-3 w-3" />
          {durationSec < 1 ? `${run.durationMs}ms` : `${durationSec}s`}
        </span>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <MetricTile
          label="Trades"
          value={String(run.tradeCount)}
          delta={tradeCountDelta !== 0 ? tradeCountDelta : undefined}
          subtext={`orig: ${run.originalTradeCount}`}
        />
        <MetricTile
          label="Win Rate"
          value={`${winRate.toFixed(1)}%`}
        />
        <MetricTile
          label="Total R"
          value={formatR(run.totalR)}
          delta={totalRDelta !== 0 ? totalRDelta : undefined}
          subtext={`orig: ${formatR(run.originalTotalR)}`}
        />
        <MetricTile
          label="Avg R"
          value={formatR(run.avgR)}
        />
      </div>

      <div className="grid grid-cols-2 gap-4 mt-4 pt-4 border-t border-white/5">
        <MetricTile
          label="Avg Winner R"
          value={run.avgWinnerR !== null ? formatR(run.avgWinnerR) : '—'}
        />
        <MetricTile
          label="Avg Loser R"
          value={run.avgLoserR !== null ? formatR(run.avgLoserR) : '—'}
        />
      </div>
    </div>
  );
}
