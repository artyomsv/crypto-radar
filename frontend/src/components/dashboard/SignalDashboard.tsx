import { useState } from 'react';
import { useSignalData } from '@/hooks/useSignalData';
import { Zap, Loader2, TrendingUp, TrendingDown, Minus, AlertTriangle, RefreshCw, ChevronDown, ChevronUp } from 'lucide-react';
import { formatPrice, formatTimeAgo } from '@/lib/utils';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { TradingSignal, DimensionScore } from '@/types';

const SIGNAL_STYLES: Record<string, { badge: string; border: string; glow: string }> = {
  STRONG_BUY: {
    badge: 'bg-gain/20 text-gain border border-gain font-bold text-sm',
    border: 'border-gain/60',
    glow: 'glow-green-strong',
  },
  BUY: {
    badge: 'bg-gain/10 text-gain border border-gain/50 text-sm',
    border: 'border-gain/30',
    glow: '',
  },
  NEUTRAL: {
    badge: 'bg-muted/10 text-muted border border-muted/30 text-sm',
    border: 'border-muted/20',
    glow: '',
  },
  SELL: {
    badge: 'bg-loss/10 text-loss border border-loss/50 text-sm',
    border: 'border-loss/30',
    glow: '',
  },
  STRONG_SELL: {
    badge: 'bg-loss/20 text-loss border border-loss font-bold text-sm',
    border: 'border-loss/60',
    glow: 'glow-red-strong',
  },
};

function getSignalStyle(signal: string) {
  return SIGNAL_STYLES[signal] ?? SIGNAL_STYLES.NEUTRAL;
}

function getConfidenceColor(confidence: number): string {
  if (confidence >= 80) return '#10b981';
  if (confidence >= 60) return '#eab308';
  return '#64748b';
}

function getScoreColor(score: number): string {
  if (score >= 50) return 'text-gain';
  if (score >= 20) return 'text-gain-light';
  if (score > -20) return 'text-text-secondary';
  if (score > -50) return 'text-loss-light';
  return 'text-loss';
}

function getBiasStyle(bias: string): { text: string; color: string } {
  switch (bias) {
    case 'BULLISH': return { text: 'BULLISH', color: 'text-gain' };
    case 'BEARISH': return { text: 'BEARISH', color: 'text-loss' };
    default: return { text: 'NEUTRAL', color: 'text-text-secondary' };
  }
}

function formatSignalLabel(signal: string): string {
  return signal.replace(/_/g, ' ');
}

function ConfidenceGauge({ confidence }: { confidence: number }) {
  const radius = 28;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (confidence / 100) * circumference;
  const color = getConfidenceColor(confidence);

  return (
    <div className="relative inline-flex items-center justify-center">
      <svg width="72" height="72" viewBox="0 0 72 72">
        <circle
          cx="36" cy="36" r={radius}
          fill="none"
          stroke="#1e293b"
          strokeWidth="5"
        />
        <circle
          cx="36" cy="36" r={radius}
          fill="none"
          stroke={color}
          strokeWidth="5"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          transform="rotate(-90 36 36)"
          className="transition-all duration-700"
        />
      </svg>
      <div className="absolute flex flex-col items-center">
        <span className="text-sm font-bold font-mono" style={{ color }}>
          {confidence}%
        </span>
      </div>
    </div>
  );
}

function DimensionBar({ dimension }: { dimension: DimensionScore }) {
  const normalizedScore = Math.max(-100, Math.min(100, dimension.score));
  const barWidth = Math.abs(normalizedScore) / 2;
  const isPositive = normalizedScore >= 0;

  return (
    <div className="space-y-0.5">
      <div className="flex items-center justify-between text-[10px]">
        <span className="text-text-secondary truncate max-w-[100px]">{dimension.name}</span>
        <span className={`font-mono font-medium ${getScoreColor(normalizedScore)}`}>
          {normalizedScore > 0 ? '+' : ''}{normalizedScore.toFixed(0)}
        </span>
      </div>
      <div className="relative h-1.5 bg-surface rounded-full overflow-hidden">
        <div className="absolute top-0 left-1/2 w-px h-full bg-muted/30" />
        {isPositive ? (
          <div
            className="absolute top-0 left-1/2 h-full bg-gain rounded-r-full transition-all duration-500"
            style={{ width: `${barWidth}%` }}
          />
        ) : (
          <div
            className="absolute top-0 h-full bg-loss rounded-l-full transition-all duration-500"
            style={{ width: `${barWidth}%`, right: '50%' }}
          />
        )}
      </div>
    </div>
  );
}

function SignalCard({ signal, isExpanded, onToggle }: { signal: TradingSignal; isExpanded: boolean; onToggle: () => void }) {
  const style = getSignalStyle(signal.signal);
  const symbolName = SYMBOL_NAMES[signal.symbol] ?? signal.symbol.replace('USDT', '');
  const symbolIcon = SYMBOL_ICONS[signal.symbol] ?? '';
  const isNeutral = signal.signal === 'NEUTRAL';
  const isOpportunity = signal.alertLevel === 'OPPORTUNITY';
  const hasTransition = signal.previousSignal && signal.previousSignal !== signal.signal;
  const hasTradeInfo = signal.suggestedEntry !== null && (signal.signal === 'STRONG_BUY' || signal.signal === 'BUY' || signal.signal === 'SELL' || signal.signal === 'STRONG_SELL');

  return (
    <div
      className={`glass-card border ${style.border} ${style.glow} transition-all duration-300 ${
        isNeutral ? 'opacity-60' : ''
      } ${isOpportunity ? 'signal-pulse' : ''}`}
    >
      <div className="p-4">
        {/* Header: symbol + signal badge */}
        <div className="flex items-start justify-between mb-3">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-lg bg-surface-light flex items-center justify-center text-lg font-bold text-accent">
              {symbolIcon || symbolName.charAt(0)}
            </div>
            <div>
              <div className="flex items-center gap-1.5">
                <span className="text-sm font-semibold text-text-primary">{symbolName}</span>
                <span className="text-[10px] text-text-secondary font-mono">{signal.symbol.replace('USDT', '')}</span>
              </div>
              <span className="text-[10px] text-text-secondary">{formatTimeAgo(signal.timestamp)}</span>
            </div>
          </div>
          <div className="flex flex-col items-end gap-1">
            <span className={`px-2.5 py-1 rounded-md ${style.badge} tracking-wide`}>
              {formatSignalLabel(signal.signal)}
            </span>
            {isOpportunity && (
              <span className="opportunity-badge inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold bg-accent/20 text-accent border border-accent/40">
                <Zap className="h-2.5 w-2.5" /> OPPORTUNITY
              </span>
            )}
          </div>
        </div>

        {/* Signal transition */}
        {hasTransition && (
          <div className="signal-transition flex items-center gap-1.5 mb-3 px-2 py-1 rounded bg-surface-light/50 text-[10px]">
            <span className={getSignalStyle(signal.previousSignal).badge.includes('text-gain') ? 'text-gain' : signal.previousSignal.includes('SELL') ? 'text-loss' : 'text-text-secondary'}>
              {formatSignalLabel(signal.previousSignal)}
            </span>
            <span className="text-text-secondary">-&gt;</span>
            <span className={style.badge.includes('text-gain') ? 'text-gain' : signal.signal.includes('SELL') ? 'text-loss' : 'text-text-secondary'}>
              {formatSignalLabel(signal.signal)}
            </span>
          </div>
        )}

        {/* Score + Confidence row */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-4">
            <div>
              <p className="text-[10px] text-text-secondary mb-0.5">Score</p>
              <p className={`text-2xl font-bold font-mono ${getScoreColor(signal.overallScore)}`}>
                {signal.overallScore > 0 ? '+' : ''}{signal.overallScore.toFixed(0)}
              </p>
            </div>
          </div>
          <ConfidenceGauge confidence={signal.confidence} />
        </div>

        {/* Dimension bars */}
        <div className="space-y-1.5 mb-3">
          {signal.dimensions.slice(0, isExpanded ? undefined : 4).map((dim) => (
            <DimensionBar key={dim.name} dimension={dim} />
          ))}
        </div>

        {/* Top reasons (collapsed: first 2) */}
        {!isExpanded && signal.dimensions.length > 0 && (
          <div className="space-y-0.5 mb-3">
            {signal.dimensions
              .flatMap((d) => d.reasons.slice(0, 1))
              .slice(0, 2)
              .map((reason, i) => (
                <p key={i} className="text-[10px] text-text-secondary leading-tight truncate">
                  {reason}
                </p>
              ))}
          </div>
        )}

        {/* Expanded: all reasons */}
        {isExpanded && (
          <div className="space-y-1.5 mb-3">
            {signal.dimensions.map((dim) => (
              dim.reasons.length > 0 && (
                <div key={dim.name}>
                  <p className="text-[10px] font-medium text-text-secondary">{dim.name}:</p>
                  {dim.reasons.map((r, i) => (
                    <p key={i} className="text-[10px] text-text-secondary/80 pl-2 leading-tight">{r}</p>
                  ))}
                </div>
              )
            ))}
          </div>
        )}

        {/* Trade suggestion */}
        {hasTradeInfo && (
          <div className="rounded-lg bg-surface-light/60 p-2.5 mb-2">
            <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-[11px]">
              {signal.suggestedEntry !== null && (
                <div className="flex justify-between">
                  <span className="text-text-secondary">Entry</span>
                  <span className="font-mono text-text-primary">{formatPrice(signal.suggestedEntry)}</span>
                </div>
              )}
              {signal.suggestedStopLoss !== null && (
                <div className="flex justify-between">
                  <span className="text-text-secondary">Stop</span>
                  <span className="font-mono text-loss">{formatPrice(signal.suggestedStopLoss)}</span>
                </div>
              )}
              {signal.suggestedTakeProfit !== null && (
                <div className="flex justify-between">
                  <span className="text-text-secondary">Target</span>
                  <span className="font-mono text-gain">{formatPrice(signal.suggestedTakeProfit)}</span>
                </div>
              )}
              {signal.riskRewardRatio !== null && (
                <div className="flex justify-between">
                  <span className="text-text-secondary">R:R</span>
                  <span className={`font-mono font-medium ${signal.riskRewardRatio >= 2 ? 'text-gain' : signal.riskRewardRatio >= 1 ? 'text-text-primary' : 'text-loss'}`}>
                    1:{signal.riskRewardRatio.toFixed(1)}
                  </span>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Expand toggle */}
        <button
          onClick={onToggle}
          className="w-full flex items-center justify-center gap-1 text-[10px] text-text-secondary hover:text-accent transition-colors pt-1"
        >
          {isExpanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
          {isExpanded ? 'Less' : 'More'}
        </button>
      </div>
    </div>
  );
}

function MarketBiasSummary({ overview }: { overview: NonNullable<ReturnType<typeof useSignalData>['overview']> }) {
  const bias = getBiasStyle(overview.marketBias);
  const top = overview.topOpportunity;

  return (
    <section className="glass-card p-5">
      <div className="flex items-center gap-2 mb-4">
        <Zap className="h-5 w-5 text-accent" />
        <h2 className="text-lg font-semibold text-text-primary">Market Signal Summary</h2>
        <span className="text-[10px] text-text-secondary ml-auto">{formatTimeAgo(overview.timestamp)}</span>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Market Bias */}
        <div className="flex flex-col items-center justify-center py-2">
          <p className="text-xs text-text-secondary mb-1">Market Bias</p>
          <p className={`text-4xl font-black tracking-tight ${bias.color}`}>
            {bias.text}
          </p>
          <div className="flex items-center gap-1 mt-1">
            {overview.marketBias === 'BULLISH' && <TrendingUp className="h-5 w-5 text-gain" />}
            {overview.marketBias === 'BEARISH' && <TrendingDown className="h-5 w-5 text-loss" />}
            {overview.marketBias === 'NEUTRAL' && <Minus className="h-5 w-5 text-muted" />}
          </div>
        </div>

        {/* Signal Distribution */}
        <div className="space-y-2">
          <p className="text-xs text-text-secondary mb-2">Signal Distribution</p>
          <SignalDistributionRow label="Strong Buy" count={overview.strongBuyCount} color="bg-gain" total={overview.signals.length} />
          <SignalDistributionRow label="Buy" count={overview.buyCount} color="bg-gain/60" total={overview.signals.length} />
          <SignalDistributionRow label="Neutral" count={overview.neutralCount} color="bg-muted/60" total={overview.signals.length} />
          <SignalDistributionRow label="Sell" count={overview.sellCount} color="bg-loss/60" total={overview.signals.length} />
          <SignalDistributionRow label="Strong Sell" count={overview.strongSellCount} color="bg-loss" total={overview.signals.length} />
        </div>

        {/* Top Opportunity */}
        <div>
          <p className="text-xs text-text-secondary mb-2">Top Opportunity</p>
          {top ? (
            <div className="rounded-lg bg-surface-light/50 p-3 border border-accent/20">
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                  <span className="text-lg font-bold">
                    {SYMBOL_ICONS[top.symbol] ?? ''}
                  </span>
                  <span className="text-sm font-semibold text-text-primary">
                    {SYMBOL_NAMES[top.symbol] ?? top.symbol.replace('USDT', '')}
                  </span>
                </div>
                <span className={`px-2 py-0.5 rounded text-xs font-bold ${getSignalStyle(top.signal).badge}`}>
                  {formatSignalLabel(top.signal)}
                </span>
              </div>
              <div className="flex items-center gap-4 text-xs">
                <div>
                  <span className="text-text-secondary">Confidence </span>
                  <span className="font-mono font-bold" style={{ color: getConfidenceColor(top.confidence) }}>
                    {top.confidence}%
                  </span>
                </div>
                <div>
                  <span className="text-text-secondary">Score </span>
                  <span className={`font-mono font-bold ${getScoreColor(top.overallScore)}`}>
                    {top.overallScore > 0 ? '+' : ''}{top.overallScore.toFixed(0)}
                  </span>
                </div>
                {top.riskRewardRatio !== null && (
                  <div>
                    <span className="text-text-secondary">R:R </span>
                    <span className="font-mono font-bold text-text-primary">1:{top.riskRewardRatio.toFixed(1)}</span>
                  </div>
                )}
              </div>
              {top.dimensions.length > 0 && (
                <div className="mt-2 space-y-0.5">
                  {top.dimensions
                    .flatMap((d) => d.reasons.slice(0, 1))
                    .slice(0, 3)
                    .map((reason, i) => (
                      <p key={i} className="text-[10px] text-text-secondary leading-tight truncate">{reason}</p>
                    ))}
                </div>
              )}
            </div>
          ) : (
            <div className="rounded-lg bg-surface-light/30 p-4 flex flex-col items-center justify-center text-center h-full">
              <Minus className="h-6 w-6 text-muted mb-1" />
              <p className="text-xs text-text-secondary">No standout opportunity</p>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function SignalDistributionRow({ label, count, color, total }: { label: string; count: number; color: string; total: number }) {
  const pct = total > 0 ? (count / total) * 100 : 0;
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="text-text-secondary w-20 text-right">{label}</span>
      <div className="flex-1 h-2 bg-surface rounded-full overflow-hidden">
        <div className={`h-full ${color} rounded-full transition-all duration-500`} style={{ width: `${pct}%` }} />
      </div>
      <span className="font-mono text-text-primary w-6 text-right">{count}</span>
    </div>
  );
}

function OpportunityAlerts({ signals }: { signals: TradingSignal[] }) {
  const opportunities = signals.filter((s) => s.alertLevel === 'OPPORTUNITY');
  const transitions = signals.filter((s) => s.previousSignal && s.previousSignal !== s.signal);

  if (opportunities.length === 0 && transitions.length === 0) return null;

  return (
    <div className="flex flex-wrap items-center gap-2">
      {opportunities.map((s) => (
        <div
          key={s.symbol + '-opp'}
          className="opportunity-badge inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-accent/10 border border-accent/30 text-xs"
        >
          <Zap className="h-3.5 w-3.5 text-accent" />
          <span className="text-accent font-semibold">{SYMBOL_NAMES[s.symbol] ?? s.symbol.replace('USDT', '')}</span>
          <span className="text-text-secondary">-</span>
          <span className={getSignalStyle(s.signal).badge.includes('text-gain') ? 'text-gain font-bold' : 'text-loss font-bold'}>
            {formatSignalLabel(s.signal)}
          </span>
        </div>
      ))}
      {transitions.map((s) => (
        <div
          key={s.symbol + '-trans'}
          className="signal-transition inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-surface-light/50 border border-surface-border text-xs"
        >
          <AlertTriangle className="h-3 w-3 text-accent" />
          <span className="text-text-primary font-medium">{(SYMBOL_NAMES[s.symbol] ?? s.symbol.replace('USDT', '')).slice(0, 5)}</span>
          <span className="text-text-secondary">{formatSignalLabel(s.previousSignal)}</span>
          <span className="text-text-secondary">-&gt;</span>
          <span className={s.signal.includes('BUY') ? 'text-gain font-semibold' : s.signal.includes('SELL') ? 'text-loss font-semibold' : 'text-text-secondary font-semibold'}>
            {formatSignalLabel(s.signal)}
          </span>
        </div>
      ))}
    </div>
  );
}

export function SignalDashboard() {
  const { overview, loading, connected, refresh } = useSignalData();
  const [expandedCards, setExpandedCards] = useState<Set<string>>(new Set());

  const toggleCard = (symbol: string) => {
    setExpandedCards((prev) => {
      const next = new Set(prev);
      if (next.has(symbol)) next.delete(symbol);
      else next.add(symbol);
      return next;
    });
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="flex flex-col items-center gap-4">
          <Loader2 className="h-8 w-8 text-accent animate-spin" />
          <p className="text-text-secondary text-sm">Loading signal data...</p>
        </div>
      </div>
    );
  }

  if (!overview) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="flex flex-col items-center gap-4">
          <Zap className="h-8 w-8 text-muted" />
          <p className="text-text-secondary text-sm">Signal service unavailable</p>
          <button
            onClick={refresh}
            className="flex items-center gap-1.5 text-sm text-accent hover:text-accent-light transition-colors"
          >
            <RefreshCw className="h-4 w-4" /> Retry
          </button>
        </div>
      </div>
    );
  }

  const sortedSignals = [...overview.signals].sort(
    (a, b) => Math.abs(b.overallScore) - Math.abs(a.overallScore)
  );

  return (
    <div className="space-y-6">
      {/* Connection status + refresh */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold text-text-primary flex items-center gap-2">
            <Zap className="h-5 w-5 text-accent" />
            Trading Signals
          </h1>
          <div className="flex items-center gap-1.5">
            <span className={`live-dot ${connected ? 'connected' : 'disconnected'}`} />
            <span className={`text-xs ${connected ? 'text-gain' : 'text-loss'}`}>
              {connected ? 'Live' : 'Offline'}
            </span>
          </div>
        </div>
        <button
          onClick={refresh}
          className="flex items-center gap-1.5 text-xs text-text-secondary hover:text-accent transition-colors"
        >
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </button>
      </div>

      {/* Opportunity alerts bar */}
      <OpportunityAlerts signals={overview.signals} />

      {/* Market Signal Summary */}
      <MarketBiasSummary overview={overview} />

      {/* Signal Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {sortedSignals.map((signal) => (
          <SignalCard
            key={signal.symbol}
            signal={signal}
            isExpanded={expandedCards.has(signal.symbol)}
            onToggle={() => toggleCard(signal.symbol)}
          />
        ))}
      </div>

      {sortedSignals.length === 0 && (
        <div className="glass-card p-8 text-center">
          <p className="text-text-secondary text-sm">No signals generated yet. Waiting for data...</p>
        </div>
      )}
    </div>
  );
}
