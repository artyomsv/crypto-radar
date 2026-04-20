import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { formatPrice, formatTimeAgo } from '@/lib/utils';
import {
  ArrowDownRight,
  ArrowUpRight,
  BookOpen,
  CheckCircle2,
  Clock,
  HelpCircle,
  Loader2,
  RefreshCw,
  Target,
  XCircle,
} from 'lucide-react';
import type { SignalOutcomeView } from '@/types';
import { TradeChartModal } from './TradeChartModal';

const STRATEGY_SHORT: Record<string, string> = {
  'dimension-scoring': 'DIM',
  'trend-continuation': 'TC',
  'liquidity-sweep': 'LS',
};

const STRATEGY_COLORS: Record<string, string> = {
  'dimension-scoring': 'bg-accent/10 text-accent border-accent/40',
  'trend-continuation': 'bg-gain/10 text-gain border-gain/40',
  'liquidity-sweep': 'bg-yellow-500/10 text-yellow-500 border-yellow-500/40',
};

function strategyPill(strategy: string) {
  const label = STRATEGY_SHORT[strategy] ?? strategy.slice(0, 3).toUpperCase();
  const classes = STRATEGY_COLORS[strategy] ?? 'bg-muted/10 text-muted border-muted/40';
  return { label, classes };
}

function stripUsdt(symbol: string): string {
  return symbol.replace(/USDT$/, '');
}

function formatR(value: number | null): string {
  if (value === null) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}R`;
}

function rColor(value: number | null): string {
  if (value === null) return 'text-text-secondary';
  if (value > 0.5) return 'text-gain';
  if (value < -0.5) return 'text-loss';
  return 'text-text-secondary';
}

interface StatusBadgeProps {
  status: SignalOutcomeView['status'];
}

function StatusBadge({ status }: StatusBadgeProps) {
  const config: Record<SignalOutcomeView['status'], { icon: React.ComponentType<{ className?: string }>; label: string; classes: string }> = {
    PENDING:    { icon: Clock,         label: 'OPEN',    classes: 'bg-accent/10 text-accent border-accent/40' },
    HIT_TARGET: { icon: CheckCircle2,  label: 'WIN',     classes: 'bg-gain/10 text-gain border-gain/40' },
    HIT_STOP:   { icon: XCircle,       label: 'LOSS',    classes: 'bg-loss/10 text-loss border-loss/40' },
    EXPIRED:    { icon: HelpCircle,    label: 'EXPIRED', classes: 'bg-muted/10 text-muted border-muted/40' },
  };
  const { icon: Icon, label, classes } = config[status] ?? config.EXPIRED;
  return (
    <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded border text-[10px] font-semibold ${classes}`}>
      <Icon className="h-2.5 w-2.5" />
      {label}
    </span>
  );
}

interface DirectionBadgeProps {
  direction: SignalOutcomeView['direction'];
}

function DirectionBadge({ direction }: DirectionBadgeProps) {
  const isLong = direction === 'LONG';
  const Icon = isLong ? ArrowUpRight : ArrowDownRight;
  return (
    <span className={`inline-flex items-center gap-0.5 text-xs font-semibold ${isLong ? 'text-gain' : 'text-loss'}`}>
      <Icon className="h-3 w-3" />
      {direction}
    </span>
  );
}

interface TradeRowProps {
  outcome: SignalOutcomeView;
  onClick: () => void;
}

function TradeRow({ outcome, onClick }: TradeRowProps) {
  const { label: stratLabel, classes: stratClasses } = strategyPill(outcome.strategy);
  const exitPrice = outcome.closedPrice;
  return (
    <tr
      onClick={onClick}
      className="border-b border-white/5 last:border-0 cursor-pointer hover:bg-white/[0.02] transition-colors"
    >
      <td className="py-2 pl-3 pr-2 text-xs text-text-secondary whitespace-nowrap">
        {formatTimeAgo(outcome.firedAt)}
      </td>
      <td className="py-2 px-2 text-sm font-semibold text-text-primary">
        {stripUsdt(outcome.symbol)}
      </td>
      <td className="py-2 px-2">
        <span className={`inline-flex items-center px-1.5 py-0.5 rounded border text-[10px] font-bold ${stratClasses}`}>
          {stratLabel}
        </span>
      </td>
      <td className="py-2 px-2">
        <DirectionBadge direction={outcome.direction} />
      </td>
      <td className="py-2 px-2 text-xs text-text-secondary whitespace-nowrap">
        <span className="text-text-primary">{formatPrice(outcome.entryPrice)}</span>
        <span className="mx-1 text-muted">→</span>
        <span className={exitPrice != null ? 'text-text-primary' : 'text-muted'}>
          {exitPrice != null ? formatPrice(exitPrice) : 'open'}
        </span>
      </td>
      <td className={`py-2 px-2 text-xs text-right font-semibold whitespace-nowrap ${rColor(outcome.realizedRMultiple)}`}>
        {formatR(outcome.realizedRMultiple)}
      </td>
      <td className="py-2 px-2 text-right">
        <span className="text-[10px] text-text-secondary font-mono mr-1">{outcome.alignment}</span>
      </td>
      <td className="py-2 pr-3 pl-1 text-right">
        <StatusBadge status={outcome.status} />
      </td>
    </tr>
  );
}

interface SelectedTrade {
  symbol: string;
  signalId: string;
}

export function TradeLedger() {
  const [outcomes, setOutcomes] = useState<SignalOutcomeView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<SelectedTrade | null>(null);

  const fetchOutcomes = useCallback(async () => {
    const data = await api.getSignalOutcomes(undefined, 50);
    setOutcomes(data);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchOutcomes();
    const interval = setInterval(fetchOutcomes, 30_000);
    return () => clearInterval(interval);
  }, [fetchOutcomes]);

  if (loading && !outcomes) {
    return (
      <div className="glass-card p-6 flex items-center justify-center">
        <Loader2 className="h-5 w-5 text-accent animate-spin mr-2" />
        <span className="text-sm text-text-secondary">Loading trade ledger…</span>
      </div>
    );
  }

  if (!outcomes || outcomes.length === 0) {
    return (
      <div className="glass-card p-6 text-center">
        <BookOpen className="h-6 w-6 text-muted mx-auto mb-2" />
        <p className="text-sm text-text-secondary">No trades tracked yet.</p>
        <p className="text-xs text-muted mt-1">
          Actionable signals and detector fires will appear here with timestamps.
        </p>
      </div>
    );
  }

  return (
    <>
      <div className="glass-card overflow-hidden">
        <div className="flex items-center justify-between p-4 border-b border-white/5">
          <h3 className="text-sm font-semibold text-text-primary flex items-center gap-2">
            <BookOpen className="h-4 w-4 text-accent" />
            Trade Ledger
            <span className="text-xs font-normal text-text-secondary">
              ({outcomes.length} most recent)
            </span>
          </h3>
          <button
            onClick={fetchOutcomes}
            className="text-text-secondary hover:text-accent transition-colors"
            aria-label="Refresh trade ledger"
          >
            <RefreshCw className="h-3.5 w-3.5" />
          </button>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-text-secondary text-[10px] uppercase tracking-wide border-b border-white/5">
                <th className="text-left font-normal py-2 pl-3 pr-2">Time</th>
                <th className="text-left font-normal px-2">Symbol</th>
                <th className="text-left font-normal px-2">Strategy</th>
                <th className="text-left font-normal px-2">Side</th>
                <th className="text-left font-normal px-2">Entry → Exit</th>
                <th className="text-right font-normal px-2">R</th>
                <th className="text-right font-normal px-2"><Target className="h-3 w-3 inline" /></th>
                <th className="text-right font-normal pr-3 pl-1">Status</th>
              </tr>
            </thead>
            <tbody>
              {outcomes.map((o) => (
                <TradeRow
                  key={o.signalId}
                  outcome={o}
                  onClick={() => setSelected({ symbol: o.symbol, signalId: o.signalId })}
                />
              ))}
            </tbody>
          </table>
        </div>
        <div className="p-2 text-[10px] text-muted text-center border-t border-white/5">
          Click any row to see the chart with entry/exit marks for that symbol.
        </div>
      </div>

      {selected && (
        <TradeChartModal
          symbol={selected.symbol}
          highlightedSignalId={selected.signalId}
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
}
