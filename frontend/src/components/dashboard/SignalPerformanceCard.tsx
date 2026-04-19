import { useEffect, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import {
  Activity,
  CheckCircle2,
  Clock,
  Loader2,
  Percent,
  RefreshCw,
  Target,
  TrendingUp,
  XCircle,
  Zap,
  ArrowRight,
  ArrowLeftRight,
} from 'lucide-react';
import type { PerformanceReport, PerformanceSummary } from '@/types';

const PERIOD_OPTIONS = [7, 30, 90] as const;
type PeriodDays = typeof PERIOD_OPTIONS[number];

const STRATEGY_LABELS: Record<string, string> = {
  'dimension-scoring': 'Dimension Scoring',
  'trend-continuation': 'Trend Continuation',
  'liquidity-sweep': 'Liquidity Sweep',
};

function formatPct(value: number, digits = 1): string {
  if (!Number.isFinite(value)) return '∞';
  return (value * 100).toFixed(digits) + '%';
}

function formatR(value: number): string {
  if (!Number.isFinite(value)) return '∞';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}R`;
}

function formatProfitFactor(value: number): string {
  if (!Number.isFinite(value) || value > 99) return '∞';
  return value.toFixed(2);
}

function rColor(value: number): string {
  if (value > 0.5) return 'text-gain';
  if (value < -0.5) return 'text-loss';
  return 'text-text-secondary';
}

function winRateColor(value: number): string {
  if (value >= 0.6) return 'text-gain';
  if (value >= 0.4) return 'text-text-secondary';
  return 'text-loss';
}

function labelStrategy(key: string): string {
  return STRATEGY_LABELS[key] ?? key;
}

interface StageBadgeProps {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  hint?: string;
  accent?: string;
}

function StageBadge({ icon: Icon, label, value, hint, accent = 'text-accent' }: StageBadgeProps) {
  return (
    <div className="flex-1 min-w-0">
      <div className="flex items-center gap-2 text-xs text-text-secondary mb-1">
        <Icon className={`h-3.5 w-3.5 ${accent}`} />
        <span className="uppercase tracking-wide">{label}</span>
      </div>
      <div className={`text-2xl font-bold ${accent} leading-tight`}>{value}</div>
      {hint && <div className="text-xs text-muted mt-0.5">{hint}</div>}
    </div>
  );
}

interface FeedbackLoopDiagramProps {
  overall: PerformanceSummary;
}

function FeedbackLoopDiagram({ overall }: FeedbackLoopDiagramProps) {
  const fired = overall.total;
  const closed = overall.hitTarget + overall.hitStop + overall.expired;
  const winRateDisplay = closed > 0 ? formatPct(overall.winRate) : '—';

  return (
    <div className="glass-card p-5">
      <div className="flex items-center gap-2 text-xs text-text-secondary mb-4 uppercase tracking-wide">
        <ArrowLeftRight className="h-3.5 w-3.5 text-accent" />
        Closed-loop feedback
      </div>
      <div className="flex items-center gap-3">
        <StageBadge icon={Zap}         label="Fired"    value={String(fired)}       hint="Signal engine emissions" />
        <ArrowRight className="h-4 w-4 text-muted flex-shrink-0" />
        <StageBadge icon={Clock}       label="Pending"  value={String(overall.pending)} hint="Awaiting outcome" accent="text-text-primary" />
        <ArrowRight className="h-4 w-4 text-muted flex-shrink-0" />
        <StageBadge icon={CheckCircle2} label="Closed"   value={String(closed)}      hint={`${overall.hitTarget} wins / ${overall.hitStop} losses`} accent="text-text-primary" />
        <ArrowRight className="h-4 w-4 text-muted flex-shrink-0" />
        <StageBadge icon={Percent}     label="Win rate" value={winRateDisplay}       hint="Measured edge" accent={winRateColor(overall.winRate)} />
      </div>
      <div className="mt-3 text-[11px] text-muted italic">
        Metrics feed back into strategy selection — the loop that turns guesses into measurements.
      </div>
    </div>
  );
}

interface KpiGridProps {
  summary: PerformanceSummary;
}

function KpiGrid({ summary }: KpiGridProps) {
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
      <div className="glass-card p-4">
        <div className="flex items-center gap-1.5 text-xs text-text-secondary mb-1">
          <Target className="h-3 w-3" /> TOTAL R
        </div>
        <div className={`text-2xl font-bold ${rColor(summary.totalRMultiple)}`}>
          {formatR(summary.totalRMultiple)}
        </div>
        <div className="text-[11px] text-muted mt-0.5">Cumulative risk multiples</div>
      </div>
      <div className="glass-card p-4">
        <div className="flex items-center gap-1.5 text-xs text-text-secondary mb-1">
          <TrendingUp className="h-3 w-3" /> AVG R
        </div>
        <div className={`text-2xl font-bold ${rColor(summary.avgRMultiple)}`}>
          {formatR(summary.avgRMultiple)}
        </div>
        <div className="text-[11px] text-muted mt-0.5">Per closed signal</div>
      </div>
      <div className="glass-card p-4">
        <div className="flex items-center gap-1.5 text-xs text-text-secondary mb-1">
          <Activity className="h-3 w-3" /> PROFIT FACTOR
        </div>
        <div className="text-2xl font-bold text-text-primary">
          {formatProfitFactor(summary.profitFactor)}
        </div>
        <div className="text-[11px] text-muted mt-0.5">Gross wins / gross losses</div>
      </div>
      <div className="glass-card p-4">
        <div className="flex items-center gap-1.5 text-xs text-text-secondary mb-1">
          <Percent className="h-3 w-3" /> BEST / WORST
        </div>
        <div className="text-sm font-semibold">
          <span className="text-gain">{formatR(summary.bestRMultiple)}</span>
          <span className="text-muted mx-1">/</span>
          <span className="text-loss">{formatR(summary.worstRMultiple)}</span>
        </div>
        <div className="text-[11px] text-muted mt-0.5">R multiple extremes</div>
      </div>
    </div>
  );
}

interface StrategyTableProps {
  title: string;
  groups: Record<string, PerformanceSummary>;
  labelFn?: (key: string) => string;
}

function StrategyTable({ title, groups, labelFn = labelStrategy }: StrategyTableProps) {
  const rows = Object.entries(groups).sort(
    (a, b) => b[1].totalRMultiple - a[1].totalRMultiple
  );
  if (rows.length === 0) return null;
  return (
    <div className="glass-card p-4">
      <div className="text-xs text-text-secondary uppercase tracking-wide mb-3">{title}</div>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-text-secondary text-xs border-b border-white/5">
            <th className="text-left font-normal py-1.5">Name</th>
            <th className="text-right font-normal">Trades</th>
            <th className="text-right font-normal">Win Rate</th>
            <th className="text-right font-normal">Avg R</th>
            <th className="text-right font-normal">Total R</th>
            <th className="text-right font-normal">PF</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([key, s]) => (
            <tr key={key} className="border-b border-white/5 last:border-0">
              <td className="py-2 font-medium text-text-primary">{labelFn(key)}</td>
              <td className="py-2 text-right text-text-secondary">{s.total}</td>
              <td className={`py-2 text-right font-semibold ${winRateColor(s.winRate)}`}>{formatPct(s.winRate)}</td>
              <td className={`py-2 text-right font-semibold ${rColor(s.avgRMultiple)}`}>{formatR(s.avgRMultiple)}</td>
              <td className={`py-2 text-right font-semibold ${rColor(s.totalRMultiple)}`}>{formatR(s.totalRMultiple)}</td>
              <td className="py-2 text-right text-text-secondary">{formatProfitFactor(s.profitFactor)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function SignalPerformanceCard() {
  const [periodDays, setPeriodDays] = useState<PeriodDays>(30);
  const [report, setReport] = useState<PerformanceReport | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchReport = useCallback(async () => {
    setLoading(true);
    const data = await api.getSignalMetrics(periodDays);
    setReport(data);
    setLoading(false);
  }, [periodDays]);

  useEffect(() => {
    fetchReport();
    const interval = setInterval(fetchReport, 60_000);
    return () => clearInterval(interval);
  }, [fetchReport]);

  if (loading && !report) {
    return (
      <div className="glass-card p-8 flex items-center justify-center">
        <Loader2 className="h-5 w-5 text-accent animate-spin mr-2" />
        <span className="text-sm text-text-secondary">Loading performance…</span>
      </div>
    );
  }

  if (!report) {
    return (
      <div className="glass-card p-6 text-center">
        <p className="text-sm text-text-secondary">Performance metrics unavailable</p>
      </div>
    );
  }

  const hasData = report.overall.total > 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
          <TrendingUp className="h-4 w-4 text-accent" />
          Signal Performance · {periodDays}d
        </h2>
        <div className="flex items-center gap-1">
          {PERIOD_OPTIONS.map((opt) => (
            <button
              key={opt}
              onClick={() => setPeriodDays(opt)}
              className={`px-2 py-0.5 text-xs rounded border transition-colors ${
                opt === periodDays
                  ? 'border-accent text-accent'
                  : 'border-white/10 text-text-secondary hover:text-text-primary'
              }`}
            >
              {opt}d
            </button>
          ))}
          <button
            onClick={fetchReport}
            className="ml-1 text-text-secondary hover:text-accent transition-colors"
            aria-label="Refresh"
          >
            <RefreshCw className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {!hasData && (
        <div className="glass-card p-6 text-center">
          <XCircle className="h-6 w-6 text-muted mx-auto mb-2" />
          <p className="text-sm text-text-secondary">No tracked outcomes yet in this window.</p>
          <p className="text-xs text-muted mt-1">
            The engine must emit actionable signals for the feedback loop to populate.
          </p>
        </div>
      )}

      {hasData && (
        <>
          <FeedbackLoopDiagram overall={report.overall} />
          <KpiGrid summary={report.overall} />
          <StrategyTable title="By Strategy" groups={report.byStrategy} />
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <StrategyTable title="By Signal Type" groups={report.bySignalType} labelFn={(k) => k.replace(/_/g, ' ')} />
            <StrategyTable title="By Symbol" groups={report.bySymbol} labelFn={(k) => k} />
          </div>
        </>
      )}
    </div>
  );
}
