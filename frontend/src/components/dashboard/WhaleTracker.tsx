import { useWhaleData } from '@/hooks/useWhaleData';
import { Loader2, Activity, TrendingUp, TrendingDown, Waves, Zap } from 'lucide-react';
import { formatLargeNumber, formatPrice, formatTimeAgo } from '@/lib/utils';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { WhaleTransaction, WhaleAnalytics } from '@/types';

export function WhaleTracker() {
  const { overview, recentTrades, loading, connected } = useWhaleData();

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="flex flex-col items-center gap-4">
          <Loader2 className="h-8 w-8 text-accent animate-spin" />
          <p className="text-text-secondary text-sm">Loading whale data...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Whale Market Overview Header */}
      {overview && (
        <section className="glass-card p-5">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Waves className="h-5 w-5 text-accent" />
              <h2 className="text-lg font-semibold text-text-primary">Whale Activity</h2>
            </div>
            <div className="flex items-center gap-1.5">
              <span className={`live-dot ${connected ? 'connected' : 'disconnected'}`} />
              <span className={`text-xs ${connected ? 'text-gain' : 'text-loss'}`}>
                {connected ? 'Live' : 'Offline'}
              </span>
            </div>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-7 gap-4">
            {/* Overall Pressure */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Whale Pressure</p>
              <p className={`text-2xl font-bold font-mono ${
                overview.overallPressure > 20 ? 'text-gain' :
                overview.overallPressure < -20 ? 'text-loss' : 'text-yellow-400'
              }`}>
                {overview.overallPressure > 0 ? '+' : ''}{overview.overallPressure.toFixed(0)}
              </p>
              <p className={`text-xs font-medium ${
                overview.overallPressure > 20 ? 'text-gain' :
                overview.overallPressure < -20 ? 'text-loss' : 'text-yellow-400'
              }`}>{overview.overallPressureLabel}</p>
            </div>

            {/* Total Volume */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">24h Whale Volume</p>
              <p className="text-2xl font-bold font-mono text-text-primary">
                {formatLargeNumber(overview.totalWhaleVolume24h)}
              </p>
              <p className="text-xs text-text-secondary">{overview.totalTradeCount24h} trades</p>
            </div>

            {/* Buy Volume */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary flex items-center gap-1">
                <TrendingUp className="h-3 w-3 text-gain" /> Buy Volume
              </p>
              <p className="text-xl font-bold font-mono text-gain">
                {formatLargeNumber(overview.totalBuyVolume24h)}
              </p>
            </div>

            {/* Sell Volume */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary flex items-center gap-1">
                <TrendingDown className="h-3 w-3 text-loss" /> Sell Volume
              </p>
              <p className="text-xl font-bold font-mono text-loss">
                {formatLargeNumber(overview.totalSellVolume24h)}
              </p>
            </div>

            {/* Most Bought */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Most Bought</p>
              <p className="text-lg font-bold text-gain">
                {SYMBOL_NAMES[overview.mostBought] || overview.mostBought || '—'}
              </p>
            </div>

            {/* Most Sold */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Most Sold</p>
              <p className="text-lg font-bold text-loss">
                {SYMBOL_NAMES[overview.mostSold] || overview.mostSold || '—'}
              </p>
            </div>

            {/* Active Symbols */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Active Symbols</p>
              <p className="text-2xl font-bold font-mono text-accent">{overview.activeSymbolCount}</p>
              <p className="text-xs text-text-secondary">of 10</p>
            </div>
          </div>
        </section>
      )}

      {/* Two-Column Layout: Flow Cards + Live Feed */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Symbol Whale Pressure Cards */}
        <div className="xl:col-span-2">
          <h3 className="text-sm font-semibold text-text-primary mb-3 flex items-center gap-2">
            <Activity className="h-4 w-4 text-accent" />
            Whale Pressure by Symbol
          </h3>
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
            {overview?.symbolAnalytics
              ?.sort((a, b) => Math.abs(b.whalePressure) - Math.abs(a.whalePressure))
              .map((analytics) => (
                <WhaleSymbolCard key={analytics.symbol} analytics={analytics} />
              ))}
          </div>
        </div>

        {/* Live Whale Trade Feed */}
        <div>
          <h3 className="text-sm font-semibold text-text-primary mb-3 flex items-center gap-2">
            <Zap className="h-4 w-4 text-accent" />
            Live Whale Trades
          </h3>
          <div className="glass-card p-3 max-h-[500px] overflow-y-auto space-y-2">
            {recentTrades.length === 0 ? (
              <p className="text-text-secondary text-sm text-center py-8">
                Waiting for whale trades...<br />
                <span className="text-xs">Threshold: $50K+</span>
              </p>
            ) : (
              recentTrades.slice(0, 30).map((tx, i) => (
                <WhaleTradeLine key={`${tx.tradeId || i}-${tx.time}`} tx={tx} />
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function WhaleSymbolCard({ analytics }: { analytics: WhaleAnalytics }) {
  const name = SYMBOL_NAMES[analytics.symbol] || analytics.symbol;
  const icon = SYMBOL_ICONS[analytics.symbol] || '?';
  const pressure = analytics.whalePressure;
  const isBullish = pressure > 10;
  const isBearish = pressure < -10;

  return (
    <div className={`glass-card p-3 space-y-2 ${
      isBullish ? 'border-gain/30' : isBearish ? 'border-loss/30' : ''
    }`}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-base font-mono text-accent">{icon}</span>
          <span className="text-xs font-medium text-text-primary">{name}</span>
        </div>
        <span className={`text-xs font-bold font-mono ${
          isBullish ? 'text-gain' : isBearish ? 'text-loss' : 'text-muted'
        }`}>
          {pressure > 0 ? '+' : ''}{pressure.toFixed(0)}
        </span>
      </div>

      {/* Pressure bar */}
      <div className="h-1.5 bg-surface-light rounded-full overflow-hidden flex">
        <div
          className="h-full bg-loss/60 rounded-l-full"
          style={{ width: `${Math.max(0, 50 - pressure / 2)}%` }}
        />
        <div
          className="h-full bg-gain/60 rounded-r-full"
          style={{ width: `${Math.max(0, 50 + pressure / 2)}%` }}
        />
      </div>

      <div className="flex justify-between text-[10px] text-text-secondary">
        <span className="text-gain">{formatLargeNumber(analytics.buyVolumeUsd1h)} buy</span>
        <span className="text-loss">{formatLargeNumber(analytics.sellVolumeUsd1h)} sell</span>
      </div>

      <div className="flex justify-between text-[10px]">
        <span className="text-text-secondary">{analytics.tradeCount1h} trades/1h</span>
        <span className={`font-medium ${
          isBullish ? 'text-gain' : isBearish ? 'text-loss' : 'text-muted'
        }`}>{analytics.pressureLabel}</span>
      </div>
    </div>
  );
}

function WhaleTradeLine({ tx }: { tx: WhaleTransaction }) {
  const isBuy = tx.side === 'BUY';
  const icon = SYMBOL_ICONS[tx.symbol] || '?';

  return (
    <div className={`flex items-center gap-2 px-2 py-1.5 rounded-md text-xs ${
      isBuy ? 'bg-gain/5 border-l-2 border-gain/40' : 'bg-loss/5 border-l-2 border-loss/40'
    }`}>
      <span className={`font-bold ${isBuy ? 'text-gain' : 'text-loss'}`}>
        {isBuy ? 'BUY' : 'SELL'}
      </span>
      <span className="font-mono text-accent">{icon}</span>
      <span className="text-text-primary font-medium">{tx.symbol.replace('USDT', '')}</span>
      <span className="text-text-secondary">@{formatPrice(tx.price)}</span>
      <span className={`ml-auto font-bold font-mono ${isBuy ? 'text-gain' : 'text-loss'}`}>
        {formatLargeNumber(tx.valueUsd)}
      </span>
      <span className="text-text-secondary text-[10px]">{formatTimeAgo(tx.time)}</span>
    </div>
  );
}
