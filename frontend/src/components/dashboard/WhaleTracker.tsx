import { useState } from 'react';
import { useWhaleData } from '@/hooks/useWhaleData';
import { Loader2, Activity, TrendingUp, TrendingDown, Waves, Zap } from 'lucide-react';
import { formatLargeNumber, formatPrice, formatTimeAgo } from '@/lib/utils';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { WhaleTransaction, WhaleAnalytics } from '@/types';

const PERIODS = [
  { value: '1d', label: '1 Day' },
  { value: '1w', label: '1 Week' },
  { value: '2w', label: '2 Weeks' },
  { value: '1m', label: '1 Month' },
  { value: '3m', label: '3 Months' },
  { value: '6m', label: '6 Months' },
  { value: '1y', label: '1 Year' },
] as const;

export function WhaleTracker() {
  const [period, setPeriod] = useState('1d');
  const { overview, recentTrades, loading, connected } = useWhaleData(period);

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
            <div className="flex items-center gap-3">
              <div className="flex gap-1">
                {PERIODS.map((p) => (
                  <button
                    key={p.value}
                    onClick={() => setPeriod(p.value)}
                    className={`px-2 py-1 text-xs font-medium rounded transition-all ${
                      period === p.value
                        ? 'bg-accent text-background'
                        : 'bg-surface-light text-text-secondary hover:text-text-primary'
                    }`}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-1.5">
                <span className={`live-dot ${connected ? 'connected' : 'disconnected'}`} />
                <span className={`text-xs ${connected ? 'text-gain' : 'text-loss'}`}>
                  {connected ? 'Live' : 'Offline'}
                </span>
              </div>
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
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4">
            {overview?.symbolAnalytics
              ?.sort((a, b) => (b.buyVolumeUsd1h + b.sellVolumeUsd1h) - (a.buyVolumeUsd1h + a.sellVolumeUsd1h))
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
  const totalVol1h = analytics.buyVolumeUsd1h + analytics.sellVolumeUsd1h;

  return (
    <div className={`glass-card p-4 space-y-3 ${
      isBullish ? 'border-gain/30' : isBearish ? 'border-loss/30' : ''
    }`}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-2xl font-mono text-accent">{icon}</span>
          <div>
            <p className="text-sm font-semibold text-text-primary">{name}</p>
            <p className="text-xs text-text-secondary">{analytics.symbol.replace('USDT', '')}</p>
          </div>
        </div>
        <div className="text-right">
          <p className={`text-xl font-bold font-mono ${
            isBullish ? 'text-gain' : isBearish ? 'text-loss' : 'text-muted'
          }`}>
            {pressure > 0 ? '+' : ''}{pressure.toFixed(0)}
          </p>
          <p className={`text-[10px] font-medium ${
            isBullish ? 'text-gain' : isBearish ? 'text-loss' : 'text-muted'
          }`}>{analytics.pressureLabel}</p>
        </div>
      </div>

      {/* Pressure bar */}
      <div className="h-2 bg-surface-light rounded-full overflow-hidden flex">
        <div
          className="h-full bg-loss/60 rounded-l-full transition-all duration-500"
          style={{ width: `${Math.max(0, 50 - pressure / 2)}%` }}
        />
        <div
          className="h-full bg-gain/60 rounded-r-full transition-all duration-500"
          style={{ width: `${Math.max(0, 50 + pressure / 2)}%` }}
        />
      </div>

      {/* 1h stats */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        <div>
          <p className="text-text-secondary text-[10px]">Buy (1h)</p>
          <p className="text-gain font-mono font-medium">{formatLargeNumber(analytics.buyVolumeUsd1h)}</p>
        </div>
        <div className="text-right">
          <p className="text-text-secondary text-[10px]">Sell (1h)</p>
          <p className="text-loss font-mono font-medium">{formatLargeNumber(analytics.sellVolumeUsd1h)}</p>
        </div>
      </div>

      {/* Net flow + trades */}
      <div className="flex justify-between items-center text-xs border-t border-surface-border pt-2">
        <span className="text-text-secondary">{analytics.tradeCount1h} trades/1h</span>
        <span className={`font-mono font-medium ${analytics.netFlowUsd1h >= 0 ? 'text-gain' : 'text-loss'}`}>
          Net: {analytics.netFlowUsd1h >= 0 ? '+' : ''}{formatLargeNumber(analytics.netFlowUsd1h)}
        </span>
      </div>

      {/* 24h volume */}
      <div className="text-[10px] text-text-secondary">
        24h: {formatLargeNumber(analytics.buyVolumeUsd24h + analytics.sellVolumeUsd24h)} ({analytics.tradeCount24h} trades)
      </div>
    </div>
  );
}

function getTradeExplorerUrl(tx: WhaleTransaction): string | null {
  if (tx.source === 'whale-alert' && tx.txHash && tx.blockchain) {
    const explorers: Record<string, string> = {
      bitcoin: 'https://blockchain.com/btc/tx/',
      ethereum: 'https://etherscan.io/tx/',
      solana: 'https://solscan.io/tx/',
      ripple: 'https://xrpscan.com/tx/',
      cardano: 'https://cardanoscan.io/transaction/',
    };
    const base = explorers[tx.blockchain];
    if (base) return base + tx.txHash;
  }
  if (tx.source === 'binance') {
    return `https://www.binance.com/en/trade/${tx.symbol.replace('USDT', '_USDT')}`;
  }
  return null;
}

function WhaleTradeLine({ tx }: { tx: WhaleTransaction }) {
  const isBuy = tx.side === 'BUY';
  const icon = SYMBOL_ICONS[tx.symbol] || '?';
  const explorerUrl = getTradeExplorerUrl(tx);
  const sourceLabel = tx.source === 'whale-alert' ? 'WA' : 'BN';

  return (
    <div className={`flex items-center gap-2 px-2 py-1.5 rounded-md text-xs ${
      isBuy ? 'bg-gain/5 border-l-2 border-gain/40' : 'bg-loss/5 border-l-2 border-loss/40'
    }`}>
      <span className={`font-bold w-7 ${isBuy ? 'text-gain' : 'text-loss'}`}>
        {isBuy ? 'BUY' : 'SELL'}
      </span>
      <span className="font-mono text-accent">{icon}</span>
      <span className="text-text-primary font-medium">{tx.symbol.replace('USDT', '')}</span>
      <span className="text-text-secondary">@{formatPrice(tx.price)}</span>
      <span className={`ml-auto font-bold font-mono ${isBuy ? 'text-gain' : 'text-loss'}`}>
        {formatLargeNumber(tx.valueUsd)}
      </span>
      {explorerUrl ? (
        <a
          href={explorerUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="text-[10px] text-accent hover:underline"
          onClick={(e) => e.stopPropagation()}
        >
          {sourceLabel}
        </a>
      ) : (
        <span className="text-[10px] text-text-secondary">{sourceLabel}</span>
      )}
      <span className="text-text-secondary text-[10px] w-12 text-right">{formatTimeAgo(tx.time)}</span>
    </div>
  );
}
