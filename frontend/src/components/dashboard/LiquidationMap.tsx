import { useState, useEffect } from 'react';
import { Loader2, Target, Info } from 'lucide-react';
import { api } from '@/lib/api';
import { formatLargeNumber, formatPrice } from '@/lib/utils';
import { SYMBOL_NAMES } from '@/types';

interface LiquidationLevel {
  price: number;
  cumulativeLongLiqUsd: number;
  cumulativeShortLiqUsd: number;
  leverage: number;
}

interface LiquidationMapData {
  symbol: string;
  currentPrice: number;
  openInterestUsd: number;
  longPct: number;
  shortPct: number;
  longLiquidationLevels: LiquidationLevel[];
  shortLiquidationLevels: LiquidationLevel[];
  highestLiquidityLongPrice: number;
  highestLiquidityShortPrice: number;
  totalEstimatedLongLiqUsd: number;
  totalEstimatedShortLiqUsd: number;
}

interface LiquidationMapProps {
  symbol: string;
}

export function LiquidationMap({ symbol }: LiquidationMapProps) {
  const [data, setData] = useState<LiquidationMapData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.getLiquidationMap(symbol).then(d => {
      setData(d);
      setLoading(false);
    });
  }, [symbol]);

  if (loading) {
    return (
      <div className="flex justify-center py-6">
        <Loader2 className="h-5 w-5 text-accent animate-spin" />
      </div>
    );
  }

  if (!data || !data.longLiquidationLevels?.length) {
    return <p className="text-text-secondary text-xs text-center py-4">No liquidation data available</p>;
  }

  const name = SYMBOL_NAMES[symbol] || symbol;
  const allLevels = [
    ...data.longLiquidationLevels.map(l => ({ ...l, type: 'long' as const })),
    ...data.shortLiquidationLevels.map(l => ({ ...l, type: 'short' as const })),
  ];

  const maxValue = Math.max(
    ...allLevels.map(l => Math.max(l.cumulativeLongLiqUsd, l.cumulativeShortLiqUsd))
  );

  return (
    <div className="space-y-4">
      {/* Header stats */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
        <div>
          <p className="text-text-secondary">Current Price</p>
          <p className="font-mono font-bold text-accent text-sm">{formatPrice(data.currentPrice)}</p>
        </div>
        <div>
          <p className="text-text-secondary">Open Interest</p>
          <p className="font-mono font-bold text-text-primary text-sm">{formatLargeNumber(data.openInterestUsd)}</p>
        </div>
        <div>
          <p className="text-text-secondary">Est. Long Liq</p>
          <p className="font-mono font-bold text-loss text-sm">{formatLargeNumber(data.totalEstimatedLongLiqUsd)}</p>
        </div>
        <div>
          <p className="text-text-secondary">Est. Short Liq</p>
          <p className="font-mono font-bold text-gain text-sm">{formatLargeNumber(data.totalEstimatedShortLiqUsd)}</p>
        </div>
      </div>

      {/* Key levels */}
      <div className="flex gap-4 text-xs">
        <div className="flex items-center gap-1">
          <Target className="h-3 w-3 text-loss" />
          <span className="text-text-secondary">Long liq magnet:</span>
          <span className="font-mono text-loss font-medium">{formatPrice(data.highestLiquidityLongPrice)}</span>
        </div>
        <div className="flex items-center gap-1">
          <Target className="h-3 w-3 text-gain" />
          <span className="text-text-secondary">Short liq magnet:</span>
          <span className="font-mono text-gain font-medium">{formatPrice(data.highestLiquidityShortPrice)}</span>
        </div>
      </div>

      {/* Column headers with info tooltip */}
      <div className="flex items-center gap-2 text-[10px] text-text-secondary mb-1">
        <div className="w-8 text-right group relative cursor-help">
          <span className="flex items-center gap-0.5 justify-end">Lev <Info className="h-2.5 w-2.5" /></span>
          <div className="absolute bottom-full right-0 mb-1 w-56 p-2 bg-surface border border-surface-border rounded-lg shadow-lg text-[10px] text-text-primary hidden group-hover:block z-10 leading-relaxed">
            <p className="font-semibold text-accent mb-1">Leverage Tier</p>
            <p>Shows which traders get liquidated at each price level based on their leverage:</p>
            <p className="mt-1"><span className="text-yellow-400">100x</span> = liquidated at 1% move</p>
            <p><span className="text-yellow-400">10x</span> = liquidated at 10% move</p>
            <p><span className="text-yellow-400">2x</span> = liquidated at 50% move</p>
            <p className="mt-1 text-text-secondary">Higher leverage = closer to current price = higher risk of cascade liquidations</p>
          </div>
        </div>
        <span className="flex-1">Est. Value at Risk</span>
        <span className="w-20 text-right">Liq Price</span>
        <span className="w-12 text-right">Dist</span>
      </div>

      {/* Visual liquidation bars — sorted by price */}
      <div className="space-y-1">
        {/* Short liquidation levels (above current price) — highest price at top, closest to current at bottom */}
        {[...data.shortLiquidationLevels]
          .sort((a, b) => b.price - a.price)
          .map((level) => (
            <LiqBar
              key={`short-${level.leverage}`}
              price={level.price}
              value={level.cumulativeShortLiqUsd}
              maxValue={maxValue}
              leverage={level.leverage}
              type="short"
              currentPrice={data.currentPrice}
            />
        ))}

        {/* Current price marker */}
        <div className="flex items-center gap-2 py-1.5 px-2 bg-accent/10 rounded border border-accent/30">
          <span className="text-[10px] text-text-secondary w-8">NOW</span>
          <div className="flex-1 h-0 border-t border-dashed border-accent" />
          <span className="font-mono text-accent font-bold text-xs">{formatPrice(data.currentPrice)}</span>
        </div>

        {/* Long liquidation levels (below current price) — closest to current at top, lowest price at bottom */}
        {[...data.longLiquidationLevels]
          .sort((a, b) => b.price - a.price)
          .map((level) => (
            <LiqBar
              key={`long-${level.leverage}`}
              price={level.price}
              value={level.cumulativeLongLiqUsd}
            maxValue={maxValue}
            leverage={level.leverage}
            type="long"
            currentPrice={data.currentPrice}
          />
        ))}
      </div>

      <p className="text-[10px] text-text-secondary italic">
        Estimated based on open interest distribution across leverage tiers. Actual liquidation levels may vary.
      </p>
    </div>
  );
}

function LiqBar({ price, value, maxValue, leverage, type, currentPrice }: {
  price: number;
  value: number;
  maxValue: number;
  leverage: number;
  type: 'long' | 'short';
  currentPrice: number;
}) {
  const pctFromCurrent = Math.abs((price - currentPrice) / currentPrice * 100);
  const barPct = maxValue > 0 ? (value / maxValue) * 100 : 0;
  const isLong = type === 'long';

  return (
    <div className="flex items-center gap-2 text-[10px]">
      <span className="text-text-secondary w-8 text-right">{leverage}x</span>
      <div className="flex-1 h-4 bg-surface-light rounded overflow-hidden relative">
        <div
          className={`h-full rounded transition-all duration-500 ${isLong ? 'bg-loss/50' : 'bg-gain/50'}`}
          style={{ width: `${barPct}%` }}
        />
        <span className="absolute inset-0 flex items-center px-2 text-[10px] font-mono text-text-primary">
          {formatLargeNumber(value)}
        </span>
      </div>
      <span className={`font-mono w-20 text-right ${isLong ? 'text-loss' : 'text-gain'}`}>
        {formatPrice(price)}
      </span>
      <span className="text-text-secondary w-12 text-right">
        {pctFromCurrent.toFixed(1)}%
      </span>
    </div>
  );
}
