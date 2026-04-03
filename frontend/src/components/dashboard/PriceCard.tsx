import { Link } from 'react-router-dom';
import { TrendingUp, TrendingDown } from 'lucide-react';
import type { PriceData, MarketAnalysis } from '@/types';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import { formatPrice, formatPercent, formatLargeNumber, getTrendBadgeColor } from '@/lib/utils';
import { BullIcon, BearIcon, SealIcon } from '@/components/icons/TrendIcons';

interface PriceCardProps {
  data: PriceData;
  flash?: 'up' | 'down' | null;
  analysis?: MarketAnalysis;
}

const TREND_LABELS: Record<string, string> = {
  STRONG_BULLISH: 'Strong Bull',
  BULLISH: 'Bullish',
  NEUTRAL: 'Neutral',
  BEARISH: 'Bearish',
  STRONG_BEARISH: 'Strong Bear',
};

function TrendIcon({ trend }: { trend: string }) {
  const cls = "w-7 h-7";
  switch (trend) {
    case 'STRONG_BULLISH':
    case 'BULLISH':
      return <BullIcon className={`${cls} text-gain`} />;
    case 'BEARISH':
    case 'STRONG_BEARISH':
      return <BearIcon className={`${cls} text-loss`} />;
    default:
      return <SealIcon className={`${cls} text-muted`} />;
  }
}

export function PriceCard({ data, flash, analysis }: PriceCardProps) {
  const isPositive = data.priceChangePct24h >= 0;
  const name = SYMBOL_NAMES[data.symbol] || data.symbol;
  const icon = SYMBOL_ICONS[data.symbol] || '?';
  const trend = analysis?.trendDirection;

  return (
    <Link
      to={`/crypto/${data.symbol}`}
      className={`glass-card p-4 hover:bg-surface-light/80 transition-all duration-200 cursor-pointer group ${
        isPositive ? 'hover:glow-green' : 'hover:glow-red'
      } ${flash === 'up' ? 'price-flash-up' : flash === 'down' ? 'price-flash-down' : ''}`}
    >
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-xl font-mono text-accent">{icon}</span>
          <div>
            <p className="font-semibold text-text-primary text-sm">{name}</p>
            <p className="text-xs text-text-secondary">{data.symbol.replace('USDT', '')}</p>
          </div>
        </div>
        <div className={`flex items-center gap-1 text-xs font-medium px-2 py-1 rounded-md border ${
          isPositive
            ? 'bg-gain/10 text-gain border-gain/20'
            : 'bg-loss/10 text-loss border-loss/20'
        }`}>
          {isPositive ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
          {formatPercent(data.priceChangePct24h)}
        </div>
      </div>

      <p className="text-lg font-bold font-mono text-text-primary mb-2">
        {formatPrice(data.price)}
      </p>

      <div className="flex items-center justify-between text-xs text-text-secondary">
        <span>Vol {formatLargeNumber(data.volume24h)}</span>
        {trend && (
          <TrendIcon trend={trend} />
        )}
      </div>

      {/* Mini sparkline */}
      {data.sparkline && data.sparkline.length > 1 && (
        <div className="mt-3 h-8">
          <MiniSparkline data={data.sparkline} positive={isPositive} />
        </div>
      )}
    </Link>
  );
}

function MiniSparkline({ data, positive }: { data: number[]; positive: boolean }) {
  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;
  const height = 32;
  const width = 100;

  const points = data.map((val, i) => {
    const x = (i / (data.length - 1)) * width;
    const y = height - ((val - min) / range) * height;
    return `${x},${y}`;
  }).join(' ');

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-full" preserveAspectRatio="none">
      <polyline
        points={points}
        fill="none"
        stroke={positive ? '#10b981' : '#ef4444'}
        strokeWidth="1.5"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}
