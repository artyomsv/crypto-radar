import { useEffect, useRef, useState } from 'react';
import { CheckCircle2, TrendingUp } from 'lucide-react';
import { cn, formatTimeAgo } from '@/lib/utils';
import { api } from '@/lib/api';
import type { BacktestRun, SignalConfigVersion } from '@/types';

interface VersionHistoryListProps {
  versions: SignalConfigVersion[];
  selectedId: number | null;
  activeId: number | null;
  onSelect: (version: SignalConfigVersion) => void;
}

function WinRateTag({ run }: { run: BacktestRun }) {
  const winRate = run.tradeCount > 0 ? (run.winCount / run.tradeCount) * 100 : 0;
  const rColor = run.totalR >= 0 ? 'text-gain' : 'text-loss';
  return (
    <div className="flex items-center gap-2 mt-1 text-[10px]">
      <TrendingUp className="h-2.5 w-2.5 text-text-secondary" />
      <span className={cn('font-mono font-semibold', rColor)}>
        {run.totalR >= 0 ? '+' : ''}{run.totalR.toFixed(2)}R
      </span>
      <span className="text-text-secondary">{winRate.toFixed(0)}% WR</span>
    </div>
  );
}

export function VersionHistoryList({
  versions,
  selectedId,
  activeId,
  onSelect,
}: VersionHistoryListProps) {
  // Map from version id → BacktestRun | null (null = fetched, no run found; undefined = not yet fetched)
  const [backtestCache, setBacktestCache] = useState<Map<number, BacktestRun | null>>(new Map());
  const pendingFetches = useRef<Set<number>>(new Set());

  // Fetch backtest summary for visible versions lazily
  useEffect(() => {
    for (const v of versions) {
      if (backtestCache.has(v.id) || pendingFetches.current.has(v.id)) continue;
      pendingFetches.current.add(v.id);
      api.listBacktestRuns(v.id, 1).then((runs) => {
        const best = runs && runs.length > 0 ? runs[0] : null;
        setBacktestCache((prev) => new Map(prev).set(v.id, best));
      });
    }
  }, [versions, backtestCache]);

  if (versions.length === 0) {
    return (
      <div className="glass-card p-4 text-xs text-text-secondary text-center">
        No versions found
      </div>
    );
  }

  return (
    <div className="glass-card overflow-hidden">
      <div className="px-4 py-3 border-b border-surface-border">
        <h2 className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
          Version history
        </h2>
      </div>
      <ul className="divide-y divide-surface-border max-h-[calc(100vh-220px)] overflow-y-auto">
        {versions.map((v) => {
          const isSelected = v.id === selectedId;
          const isActive = v.id === activeId;
          const backtest = backtestCache.get(v.id);

          return (
            <li key={v.id}>
              <button
                onClick={() => onSelect(v)}
                className={cn(
                  'w-full text-left px-4 py-3 transition-colors',
                  isSelected
                    ? 'bg-accent/10 border-l-2 border-accent'
                    : 'hover:bg-surface-light/50 border-l-2 border-transparent'
                )}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <span className={cn(
                      'text-xs font-bold font-mono shrink-0',
                      isActive ? 'text-gain' : isSelected ? 'text-accent' : 'text-text-primary'
                    )}>
                      v{v.version}
                    </span>
                    {isActive && (
                      <CheckCircle2 className="h-3 w-3 text-gain shrink-0" />
                    )}
                  </div>
                  <span className="text-[10px] text-text-secondary shrink-0 whitespace-nowrap">
                    {formatTimeAgo(v.createdAt)}
                  </span>
                </div>

                <p className="text-[11px] text-text-secondary mt-0.5 leading-tight line-clamp-2">
                  {v.description}
                </p>

                {backtest !== undefined && backtest !== null && (
                  <WinRateTag run={backtest} />
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
