import { TrendingUp, TrendingDown, Minus, BarChart3, Gauge, Activity } from 'lucide-react';
import type { MarketOverview } from '@/types';
import { SYMBOL_NAMES } from '@/types';

interface MarketOverviewPanelProps {
  overview: MarketOverview;
}

function scoreColor(value: number): string {
  if (value >= 60) return 'text-gain';
  if (value >= 40) return 'text-yellow-400';
  return 'text-loss';
}

export function MarketOverviewPanel({ overview }: MarketOverviewPanelProps) {
  const fgIndex = overview.fearGreedIndex ?? 0;
  const fgLabel = overview.fearGreedLabel || 'N/A';
  const techScore = overview.technicalScore ?? 50;
  const techLabel = overview.technicalScoreLabel || 'Neutral';

  return (
    <section className="glass-card p-5">
      <div className="flex items-center gap-2 mb-4">
        <BarChart3 className="h-5 w-5 text-accent" />
        <h2 className="text-lg font-semibold text-text-primary">Market Overview</h2>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-4">
        {/* Real Fear & Greed Index */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary flex items-center gap-1">
            <Gauge className="h-3 w-3" /> Fear & Greed
          </p>
          <p className={`text-2xl font-bold font-mono ${scoreColor(fgIndex)}`}>
            {fgIndex}
          </p>
          <p className={`text-xs font-medium ${scoreColor(fgIndex)}`}>{fgLabel}</p>
        </div>

        {/* Our Technical Score */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary flex items-center gap-1">
            <Activity className="h-3 w-3" /> Technical Score
          </p>
          <p className={`text-2xl font-bold font-mono ${scoreColor(techScore)}`}>
            {techScore}
          </p>
          <p className={`text-xs font-medium ${scoreColor(techScore)}`}>{techLabel}</p>
        </div>

        {/* Bullish */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary flex items-center gap-1">
            <TrendingUp className="h-3 w-3 text-gain" /> Bullish
          </p>
          <p className="text-2xl font-bold font-mono text-gain">{overview.bullishCount}</p>
          <p className="text-xs text-text-secondary">coins</p>
        </div>

        {/* Bearish */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary flex items-center gap-1">
            <TrendingDown className="h-3 w-3 text-loss" /> Bearish
          </p>
          <p className="text-2xl font-bold font-mono text-loss">{overview.bearishCount}</p>
          <p className="text-xs text-text-secondary">coins</p>
        </div>

        {/* Neutral */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary flex items-center gap-1">
            <Minus className="h-3 w-3" /> Neutral
          </p>
          <p className="text-2xl font-bold font-mono text-text-secondary">{overview.neutralCount}</p>
          <p className="text-xs text-text-secondary">coins</p>
        </div>

        {/* Top Gainer */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary">Top Gainer</p>
          <p className="text-lg font-bold text-gain">
            {SYMBOL_NAMES[overview.topGainer] || overview.topGainer}
          </p>
          <p className="text-xs text-text-secondary">{overview.topGainer?.replace('USDT', '')}</p>
        </div>

        {/* Top Loser */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary">Top Loser</p>
          <p className="text-lg font-bold text-loss">
            {SYMBOL_NAMES[overview.topLoser] || overview.topLoser}
          </p>
          <p className="text-xs text-text-secondary">{overview.topLoser?.replace('USDT', '')}</p>
        </div>
      </div>
    </section>
  );
}
