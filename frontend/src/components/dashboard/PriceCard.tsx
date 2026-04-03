import { Link } from 'react-router-dom';
import { TrendingUp, TrendingDown } from 'lucide-react';
import type { PriceData } from '@/types';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import { formatPrice, formatPercent, formatLargeNumber } from '@/lib/utils';

interface PriceCardProps {
  data: PriceData;
}

export function PriceCard({ data }: PriceCardProps) {
  const isPositive = data.priceChangePct24h >= 0;
  const name = SYMBOL_NAMES[data.symbol] || data.symbol;
  const icon = SYMBOL_ICONS[data.symbol] || '?';

  return (
    <Link
      to={`/crypto/${data.symbol}`}
      className={`glass-card p-4 hover:bg-surface-light/80 transition-all duration-200 cursor-pointer group ${
        isPositive ? 'hover:glow-green' : 'hover:glow-red'
      }`}
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
        {data.marketCap > 0 && <span>MCap {formatLargeNumber(data.marketCap)}</span>}
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
