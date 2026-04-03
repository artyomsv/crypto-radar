import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useWhaleData } from '@/hooks/useWhaleData';
import { Loader2, Activity, TrendingUp, TrendingDown, Waves, Zap, Flame } from 'lucide-react';
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

  const exchangeTrades = recentTrades.filter(t => t.source !== 'whale-alert');
  const whaleTrades = recentTrades.filter(t => t.source === 'whale-alert');
  const exchangeDist = calcDistribution(exchangeTrades);
  const whaleDist = calcDistribution(whaleTrades);

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
      <div className="grid grid-cols-1 xl:grid-cols-[1fr_400px] gap-6">
        {/* Symbol Whale Pressure Cards */}
        <div>
          <h3 className="text-sm font-semibold text-text-primary mb-3 flex items-center gap-2">
            <Activity className="h-4 w-4 text-accent" />
            Whale Pressure by Symbol
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {overview?.symbolAnalytics
              ?.map(a => ({ ...a, sortScore: a.tradeCount1h * 10000 + a.buyVolumeUsd1h + a.sellVolumeUsd1h }))
              .sort((a, b) => b.sortScore - a.sortScore)
              .map((analytics, index) => (
                <div
                  key={analytics.symbol}
                  className="transition-all duration-700 ease-in-out"
                  style={{ order: index }}
                >
                  <WhaleSymbolCard analytics={analytics} rank={index + 1} />
                </div>
              ))}
          </div>
        </div>

        {/* Live Whale Trade Feed */}
        <div className="flex flex-col">
          <h3 className="text-sm font-semibold text-text-primary mb-3 flex items-center gap-2 shrink-0">
            <Zap className="h-4 w-4 text-accent" />
            Live Whale Trades
          </h3>
          <div className="glass-card p-3 overflow-y-auto space-y-2 flex-1 min-h-0">
            <div className="space-y-2 mb-3">
              <DistributionBar label="Exchanges" data={exchangeDist} />
              <DistributionBar label="On-Chain" data={whaleDist} />
            </div>
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

function calcDistribution(trades: WhaleTransaction[]) {
  const buyVol = trades.filter(t => t.side === 'BUY').reduce((sum, t) => sum + t.valueUsd, 0);
  const sellVol = trades.filter(t => t.side === 'SELL').reduce((sum, t) => sum + t.valueUsd, 0);
  const total = buyVol + sellVol;
  return { buyVol, sellVol, buyPct: total > 0 ? (buyVol / total) * 100 : 50, total };
}

function DistributionBar({ label, data }: { label: string; data: { buyVol: number; sellVol: number; buyPct: number; total: number } }) {
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-[10px]">
        <span className="text-text-secondary">{label}</span>
        <div className="flex gap-3">
          <span className="text-gain">{formatLargeNumber(data.buyVol)} buy</span>
          <span className="text-loss">{formatLargeNumber(data.sellVol)} sell</span>
        </div>
      </div>
      <div className="h-1.5 bg-surface-light rounded-full overflow-hidden flex">
        <div className="h-full bg-gain/70 rounded-l-full transition-all duration-500" style={{ width: `${data.buyPct}%` }} />
        <div className="h-full bg-loss/70 rounded-r-full transition-all duration-500" style={{ width: `${100 - data.buyPct}%` }} />
      </div>
    </div>
  );
}

function WhaleSymbolCard({ analytics, rank }: { analytics: WhaleAnalytics; rank?: number }) {
  const name = SYMBOL_NAMES[analytics.symbol] || analytics.symbol;
  const icon = SYMBOL_ICONS[analytics.symbol] || '?';
  const pressure = analytics.whalePressure;
  const isBullish = pressure > 10;
  const isBearish = pressure < -10;
  const totalVol1h = analytics.buyVolumeUsd1h + analytics.sellVolumeUsd1h;
  const isHot = analytics.tradeCount1h >= 5 || totalVol1h >= 500000;

  return (
    <Link to={`/crypto/${analytics.symbol}`} className={`glass-card p-5 space-y-4 block ${
      isHot
        ? isBullish ? 'border-gain/50 glow-green' : isBearish ? 'border-loss/50 glow-red' : 'border-accent/40 glow-cyan'
        : isBullish ? 'border-gain/20' : isBearish ? 'border-loss/20' : ''
    }`}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          {rank && rank <= 3 && (
            <span className={`text-[10px] font-bold w-5 h-5 rounded-full flex items-center justify-center ${
              rank === 1 ? 'bg-yellow-500/20 text-yellow-400 border border-yellow-500/40'
                : rank === 2 ? 'bg-gray-400/20 text-gray-300 border border-gray-400/40'
                  : 'bg-amber-700/20 text-amber-500 border border-amber-700/40'
            }`}>{rank}</span>
          )}
          <span className="text-3xl font-mono text-accent">{icon}</span>
          <div>
            <div className="flex items-center gap-2">
              <p className="text-base font-semibold text-text-primary">{name}</p>
              {isHot && <span className="text-[10px] px-1.5 py-0.5 rounded bg-accent/20 text-accent border border-accent/30 animate-pulse">HOT</span>}
            </div>
            <p className="text-sm text-text-secondary">{analytics.symbol.replace('USDT', '')}</p>
          </div>
        </div>
        <div className="text-right">
          <p className={`text-2xl font-bold font-mono ${
            isBullish ? 'text-gain' : isBearish ? 'text-loss' : 'text-muted'
          }`}>
            {pressure > 0 ? '+' : ''}{pressure.toFixed(0)}
          </p>
          <p className={`text-xs font-medium ${
            isBullish ? 'text-gain' : isBearish ? 'text-loss' : 'text-muted'
          }`}>{analytics.pressureLabel}</p>
        </div>
      </div>

      {/* Pressure bar */}
      <div className="h-2.5 bg-surface-light rounded-full overflow-hidden flex">
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
      <div className="grid grid-cols-2 gap-3">
        <div>
          <p className="text-text-secondary text-xs">Buy (1h)</p>
          <p className="text-gain font-mono font-semibold text-sm">{formatLargeNumber(analytics.buyVolumeUsd1h)}</p>
        </div>
        <div className="text-right">
          <p className="text-text-secondary text-xs">Sell (1h)</p>
          <p className="text-loss font-mono font-semibold text-sm">{formatLargeNumber(analytics.sellVolumeUsd1h)}</p>
        </div>
      </div>

      {/* Net flow + trades */}
      <div className="flex justify-between items-center border-t border-surface-border pt-3">
        <span className="text-text-secondary text-sm">{analytics.tradeCount1h} trades/1h</span>
        <span className={`font-mono font-semibold text-sm ${analytics.netFlowUsd1h >= 0 ? 'text-gain' : 'text-loss'}`}>
          Net: {analytics.netFlowUsd1h >= 0 ? '+' : ''}{formatLargeNumber(analytics.netFlowUsd1h)}
        </span>
      </div>

      {/* 24h summary */}
      <div className="text-xs text-text-secondary">
        24h: {formatLargeNumber(analytics.buyVolumeUsd24h + analytics.sellVolumeUsd24h)} ({analytics.tradeCount24h} trades)
      </div>
    </Link>
  );
}

const SOURCE_CONFIG: Record<string, { label: string; url: (sym: string) => string }> = {
  'binance':     { label: 'Binance',    url: (s) => `https://www.binance.com/en/trade/${s.replace('USDT', '_USDT')}` },
  'coinbase':    { label: 'Coinbase',   url: (s) => `https://www.coinbase.com/advanced-trade/spot/${s.replace('USDT', '-USD')}` },
  'kraken':      { label: 'Kraken',     url: (s) => `https://www.kraken.com/prices/${s.replace('USDT', '').toLowerCase()}` },
  'okx':         { label: 'OKX',        url: (s) => `https://www.okx.com/trade-spot/${s.replace('USDT', '-usdt').toLowerCase()}` },
  'bybit':       { label: 'Bybit',      url: (s) => `https://www.bybit.com/en/trade/spot/${s}` },
  'bitfinex':    { label: 'Bitfinex',   url: (s) => `https://trading.bitfinex.com/t/${s.replace('USDT', ':USD')}` },
  'whale-alert': { label: 'On-Chain',   url: () => 'https://whale-alert.io' },
};

function getTradeExplorerUrl(tx: WhaleTransaction): string {
  // Whale Alert with tx hash → blockchain explorer
  if (tx.source === 'whale-alert' && tx.txHash && tx.blockchain) {
    const explorers: Record<string, string> = {
      bitcoin: 'https://blockchain.com/btc/tx/',
      ethereum: 'https://etherscan.io/tx/',
      solana: 'https://solscan.io/tx/',
      ripple: 'https://xrpscan.com/tx/',
      cardano: 'https://cardanoscan.io/transaction/',
      tron: 'https://tronscan.org/#/transaction/',
      litecoin: 'https://blockchair.com/litecoin/transaction/',
    };
    const base = explorers[tx.blockchain];
    if (base) return base + tx.txHash;
  }
  // Exchange → trade page
  const config = SOURCE_CONFIG[tx.source];
  if (config) return config.url(tx.symbol);
  return '#';
}

function WhaleTradeLine({ tx }: { tx: WhaleTransaction }) {
  const isBuy = tx.side === 'BUY';
  const icon = SYMBOL_ICONS[tx.symbol] || '?';
  const explorerUrl = getTradeExplorerUrl(tx);
  const sourceLabel = SOURCE_CONFIG[tx.source]?.label || tx.source;
  const isMega = tx.valueUsd >= 200000;
  const isGiga = tx.valueUsd >= 1000000;

  return (
    <div className={`flex items-center gap-2 px-2 py-1.5 rounded-md text-xs ${
      isGiga
        ? isBuy ? 'bg-gain/20 border-l-4 border-gain animate-pulse' : 'bg-loss/20 border-l-4 border-loss animate-pulse'
        : isMega
          ? isBuy ? 'bg-gain/10 border-l-3 border-gain/70' : 'bg-loss/10 border-l-3 border-loss/70'
          : isBuy ? 'bg-gain/5 border-l-2 border-gain/40' : 'bg-loss/5 border-l-2 border-loss/40'
    }`}>
      <span className={`font-bold w-7 ${isBuy ? 'text-gain' : 'text-loss'}`}>
        {isBuy ? 'BUY' : 'SELL'}
      </span>
      <span className="font-mono text-accent">{icon}</span>
      <span className="text-text-primary font-medium">{tx.symbol.replace('USDT', '')}</span>
      <span className="text-text-secondary">@{formatPrice(tx.price)}</span>
      <span className={`ml-auto font-bold font-mono ${isBuy ? 'text-gain' : 'text-loss'} ${isGiga ? 'text-sm' : isMega ? 'text-xs' : ''}`}>
        {formatLargeNumber(tx.valueUsd)}
      </span>
      {isGiga && <Flame className="h-3.5 w-3.5 text-yellow-400 animate-pulse shrink-0" />}
      {isMega && !isGiga && <Zap className="h-3 w-3 text-accent shrink-0" />}
      <a
        href={explorerUrl}
        target="_blank"
        rel="noopener noreferrer"
        className="text-[10px] text-accent hover:underline shrink-0"
        onClick={(e) => e.stopPropagation()}
      >
        {sourceLabel}
      </a>
      <span className="text-text-secondary text-[10px] w-12 text-right">{formatTimeAgo(tx.time)}</span>
    </div>
  );
}
