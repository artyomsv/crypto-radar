import { TrendingUp, TrendingDown, Minus, BarChart3, Gauge } from 'lucide-react';
import type { MarketOverview } from '@/types';
import { SYMBOL_NAMES } from '@/types';

interface MarketOverviewPanelProps {
  overview: MarketOverview;
}

export function MarketOverviewPanel({ overview }: MarketOverviewPanelProps) {
  const sentimentColor = overview.fearGreedIndex >= 60
    ? 'text-gain'
    : overview.fearGreedIndex >= 40
      ? 'text-yellow-400'
      : 'text-loss';

  const sentimentLabel = overview.marketSentiment || (
    overview.fearGreedIndex >= 75 ? 'Extreme Greed'
      : overview.fearGreedIndex >= 60 ? 'Greed'
        : overview.fearGreedIndex >= 40 ? 'Neutral'
          : overview.fearGreedIndex >= 25 ? 'Fear'
            : 'Extreme Fear'
  );

  return (
    <section className="glass-card p-5">
      <div className="flex items-center gap-2 mb-4">
        <BarChart3 className="h-5 w-5 text-accent" />
        <h2 className="text-lg font-semibold text-text-primary">Market Overview</h2>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4">
        {/* Fear & Greed */}
        <div className="space-y-1">
          <p className="text-xs text-text-secondary flex items-center gap-1">
            <Gauge className="h-3 w-3" /> Fear & Greed
          </p>
          <p className={`text-2xl font-bold font-mono ${sentimentColor}`}>
            {overview.fearGreedIndex}
          </p>
          <p className={`text-xs font-medium ${sentimentColor}`}>{sentimentLabel}</p>
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
