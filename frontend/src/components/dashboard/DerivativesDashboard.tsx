import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useDerivativesData } from '@/hooks/useDerivativesData';
import { BarChart3, TrendingUp, TrendingDown, Zap, Activity, Loader2, Flame, ChevronDown, ChevronRight } from 'lucide-react';
import { formatLargeNumber, formatPrice, formatTimeAgo } from '@/lib/utils';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { SymbolDerivatives, LiquidationEvent } from '@/types';
import { LiquidationMap } from './LiquidationMap';

export function DerivativesDashboard() {
  const { overview, liquidations, loading, connected } = useDerivativesData();
  const [expandedSymbol, setExpandedSymbol] = useState<string | null>(null);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="flex flex-col items-center gap-4">
          <Loader2 className="h-8 w-8 text-accent animate-spin" />
          <p className="text-text-secondary text-sm">Loading derivatives data...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Market Leverage Overview */}
      {overview && (
        <section className="glass-card p-5">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <BarChart3 className="h-5 w-5 text-accent" />
              <h2 className="text-lg font-semibold text-text-primary">Market Leverage Overview</h2>
            </div>
            <div className="flex items-center gap-1.5">
              <span className={`live-dot ${connected ? 'connected' : 'disconnected'}`} />
              <span className={`text-xs ${connected ? 'text-gain' : 'text-loss'}`}>
                {connected ? 'Live' : 'Offline'}
              </span>
            </div>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4 mb-4">
            {/* Total Open Interest */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Total Open Interest</p>
              <p className="text-2xl font-bold font-mono text-text-primary">
                {formatLargeNumber(overview.totalOpenInterestUsd)}
              </p>
            </div>

            {/* Avg Funding Rate */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Avg Funding Rate</p>
              <p className={`text-2xl font-bold font-mono ${
                overview.avgFundingRate >= 0 ? 'text-gain' : 'text-loss'
              }`}>
                {overview.avgFundingRate >= 0 ? '+' : ''}{(overview.avgFundingRate * 100).toFixed(4)}%
              </p>
              <p className={`text-xs font-medium ${
                overview.fundingRateLabel === 'Bullish' ? 'text-gain' :
                overview.fundingRateLabel === 'Bearish' ? 'text-loss' : 'text-yellow-400'
              }`}>{overview.fundingRateLabel}</p>
            </div>

            {/* Total Liquidations */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary flex items-center gap-1">
                <Zap className="h-3 w-3 text-yellow-400" /> Liquidations 24h
              </p>
              <p className="text-2xl font-bold font-mono text-yellow-400">
                {formatLargeNumber(overview.totalLiquidations24h)}
              </p>
            </div>

            {/* Long Liquidations */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary flex items-center gap-1">
                <TrendingDown className="h-3 w-3 text-loss" /> Long Liq
              </p>
              <p className="text-xl font-bold font-mono text-loss">
                {formatLargeNumber(overview.longLiquidations24h)}
              </p>
            </div>

            {/* Short Liquidations */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary flex items-center gap-1">
                <TrendingUp className="h-3 w-3 text-gain" /> Short Liq
              </p>
              <p className="text-xl font-bold font-mono text-gain">
                {formatLargeNumber(overview.shortLiquidations24h)}
              </p>
            </div>

            {/* Market Sentiment */}
            <div className="space-y-1">
              <p className="text-xs text-text-secondary">Market Sentiment</p>
              <p className={`text-lg font-bold ${
                overview.marketLongPct > 55 ? 'text-gain' :
                overview.marketShortPct > 55 ? 'text-loss' : 'text-yellow-400'
              }`}>
                {overview.marketLongPct > 55 ? 'Bullish' :
                 overview.marketShortPct > 55 ? 'Bearish' : 'Neutral'}
              </p>
            </div>
          </div>

          {/* Long/Short Market Bar */}
          <div className="space-y-1">
            <div className="flex justify-between text-xs">
              <span className="text-gain">Long {overview.marketLongPct.toFixed(1)}%</span>
              <span className="text-loss">Short {overview.marketShortPct.toFixed(1)}%</span>
            </div>
            <div className="h-3 bg-surface-light rounded-full overflow-hidden flex">
              <div
                className="h-full bg-gain/70 rounded-l-full transition-all duration-500"
                style={{ width: `${overview.marketLongPct}%` }}
              />
              <div
                className="h-full bg-loss/70 rounded-r-full transition-all duration-500"
                style={{ width: `${overview.marketShortPct}%` }}
              />
            </div>
          </div>
        </section>
      )}

      {/* Two-Column Layout: Symbol Cards + Liq Map + Feed */}
      <div className="grid grid-cols-1 xl:grid-cols-[1fr_420px] gap-6">
        {/* Per-Symbol Derivatives Cards */}
        <div>
          <h3 className="text-sm font-semibold text-text-primary mb-3 flex items-center gap-2">
            <Activity className="h-4 w-4 text-accent" />
            Derivatives by Symbol
            <span className="text-[10px] text-text-secondary font-normal ml-1">Click a card to view liquidation levels</span>
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {overview?.symbolData
              ?.slice()
              .sort((a, b) => b.openInterestUsd - a.openInterestUsd)
              .map((symbol, index) => (
                <div
                  key={symbol.symbol}
                  className="transition-all duration-700 ease-in-out cursor-pointer"
                  style={{ order: index }}
                  onClick={() => setExpandedSymbol(expandedSymbol === symbol.symbol ? null : symbol.symbol)}
                >
                  <SymbolDerivativesCard data={symbol} expanded={expandedSymbol === symbol.symbol} />
                </div>
              ))}
          </div>
        </div>

        {/* Right Column: Liquidation Map + Feed */}
        <div className="flex flex-col gap-4">
          {/* Liquidation Map for selected symbol */}
          {expandedSymbol && (
            <div className="glass-card p-4">
              <h3 className="text-sm font-semibold text-accent mb-3 flex items-center gap-2">
                <BarChart3 className="h-4 w-4" />
                Liquidation Levels — {SYMBOL_NAMES[expandedSymbol] || expandedSymbol}
              </h3>
              <LiquidationMap symbol={expandedSymbol} />
            </div>
          )}

          {/* Live Liquidation Feed */}
          <div className="flex flex-col flex-1 min-h-0">
            <h3 className="text-sm font-semibold text-text-primary mb-3 flex items-center gap-2 shrink-0">
              <Flame className="h-4 w-4 text-yellow-400" />
              Live Liquidations
            </h3>
          <div className="glass-card p-3 overflow-y-auto space-y-2 flex-1 min-h-0">
            {liquidations.length === 0 ? (
              <p className="text-text-secondary text-sm text-center py-8">
                Waiting for liquidation events...
              </p>
            ) : (
              liquidations.slice(0, 50).map((liq, i) => (
                <LiquidationLine key={`${liq.symbol}-${liq.time}-${i}`} liq={liq} />
              ))
            )}
          </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function SymbolDerivativesCard({ data, expanded }: { data: SymbolDerivatives; expanded?: boolean }) {
  const name = SYMBOL_NAMES[data.symbol] || data.symbol;
  const icon = SYMBOL_ICONS[data.symbol] || '?';
  const isBullish = data.sentiment === 'Bullish' || data.sentiment === 'BULLISH';
  const isBearish = data.sentiment === 'Bearish' || data.sentiment === 'BEARISH';

  return (
    <div className={`glass-card p-5 space-y-4 hover:bg-surface-light/30 transition-colors ${
      isBullish ? 'border-gain/20' : isBearish ? 'border-loss/20' : ''
    } ${expanded ? 'border-accent/40' : ''}`}>
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="text-text-secondary">
            {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </span>
          <span className="text-3xl font-mono text-accent">{icon}</span>
          <div>
            <p className="text-base font-semibold text-text-primary">{name}</p>
            <p className="text-sm text-text-secondary">{data.symbol.replace('USDT', '')}</p>
          </div>
        </div>
        <div className="text-right">
          <p className={`text-xs font-medium px-2 py-0.5 rounded ${
            isBullish ? 'bg-gain/20 text-gain' :
            isBearish ? 'bg-loss/20 text-loss' : 'bg-muted/20 text-text-secondary'
          }`}>{data.sentiment}</p>
        </div>
      </div>

      {/* Funding Rate */}
      <div className="flex justify-between items-center">
        <div>
          <p className="text-xs text-text-secondary">Funding Rate</p>
          <p className={`text-lg font-bold font-mono ${
            data.fundingRate >= 0 ? 'text-gain' : 'text-loss'
          }`}>
            {data.fundingRate >= 0 ? '+' : ''}{(data.fundingRate * 100).toFixed(4)}%
          </p>
        </div>
        <div className="text-right">
          <p className="text-xs text-text-secondary">Annualized</p>
          <p className={`text-sm font-semibold font-mono ${
            data.fundingRateAnnualized >= 0 ? 'text-gain' : 'text-loss'
          }`}>
            {data.fundingRateAnnualized >= 0 ? '+' : ''}{data.fundingRateAnnualized.toFixed(2)}%
          </p>
        </div>
      </div>

      {/* Open Interest */}
      <div>
        <p className="text-xs text-text-secondary">Open Interest</p>
        <p className="text-lg font-bold font-mono text-text-primary">
          {formatLargeNumber(data.openInterestUsd)}
        </p>
      </div>

      {/* Long/Short Bar */}
      <div className="space-y-1">
        <div className="flex justify-between text-[10px]">
          <span className="text-gain">Long {data.longPct.toFixed(1)}%</span>
          <span className="text-loss">Short {data.shortPct.toFixed(1)}%</span>
        </div>
        <div className="h-2.5 bg-surface-light rounded-full overflow-hidden flex">
          <div
            className="h-full bg-gain/60 rounded-l-full transition-all duration-500"
            style={{ width: `${data.longPct}%` }}
          />
          <div
            className="h-full bg-loss/60 rounded-r-full transition-all duration-500"
            style={{ width: `${data.shortPct}%` }}
          />
        </div>
      </div>

      {/* Liquidation Volume */}
      <div className="flex justify-between items-center border-t border-surface-border pt-3">
        <span className="text-text-secondary text-xs flex items-center gap-1">
          <Zap className="h-3 w-3 text-yellow-400" /> Liquidations 24h
        </span>
        <span className="font-mono font-semibold text-sm text-yellow-400">
          {formatLargeNumber(data.liquidations24hUsd)}
        </span>
      </div>
    </div>
  );
}

function LiquidationLine({ liq }: { liq: LiquidationEvent }) {
  const isLongLiquidated = liq.side === 'LONG';
  const icon = SYMBOL_ICONS[liq.symbol] || '?';
  const isMega = liq.valueUsd >= 500000;
  const isGiga = liq.valueUsd >= 2000000;

  return (
    <div className={`flex items-center gap-2 px-2 py-1.5 rounded-md text-xs ${
      isGiga
        ? isLongLiquidated ? 'bg-loss/20 border-l-4 border-loss animate-pulse' : 'bg-gain/20 border-l-4 border-gain animate-pulse'
        : isMega
          ? isLongLiquidated ? 'bg-loss/10 border-l-3 border-loss/70' : 'bg-gain/10 border-l-3 border-gain/70'
          : isLongLiquidated ? 'bg-loss/5 border-l-2 border-loss/40' : 'bg-gain/5 border-l-2 border-gain/40'
    }`}>
      {isLongLiquidated ? (
        <TrendingDown className="h-3.5 w-3.5 text-loss shrink-0" />
      ) : (
        <TrendingUp className="h-3.5 w-3.5 text-gain shrink-0" />
      )}
      <span className={`font-bold w-12 ${isLongLiquidated ? 'text-loss' : 'text-gain'}`}>
        {liq.side}
      </span>
      <span className="font-mono text-accent">{icon}</span>
      <span className="text-text-primary font-medium">{liq.symbol.replace('USDT', '')}</span>
      <span className="text-text-secondary">@{formatPrice(liq.price)}</span>
      <span className={`ml-auto font-bold font-mono ${
        isLongLiquidated ? 'text-loss' : 'text-gain'
      } ${isGiga ? 'text-sm' : ''}`}>
        {formatLargeNumber(liq.valueUsd)}
      </span>
      {isGiga && <Flame className="h-3.5 w-3.5 text-yellow-400 animate-pulse shrink-0" />}
      {isMega && !isGiga && <Zap className="h-3 w-3 text-yellow-400 shrink-0" />}
      <span className="text-text-secondary text-[10px] w-12 text-right shrink-0">{formatTimeAgo(liq.time)}</span>
    </div>
  );
}
