import { useState, useEffect, useRef, useCallback } from 'react';
import { Layers, RefreshCw } from 'lucide-react';
import { api } from '@/lib/api';
import { formatPrice, formatLargeNumber } from '@/lib/utils';
import type { OrderBookDepth, PriceLevelData } from '@/types';

interface DepthChartProps {
  symbol: string;
}

const REFRESH_INTERVAL_MS = 10_000;

export function DepthChart({ symbol }: DepthChartProps) {
  const [depth, setDepth] = useState<OrderBookDepth | null>(null);
  const [loading, setLoading] = useState(true);
  const [hoveredLevel, setHoveredLevel] = useState<{ side: 'bid' | 'ask'; index: number } | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  const fetchDepth = useCallback(async () => {
    const data = await api.getOrderBookDepth(symbol);
    if (data) setDepth(data);
    setLoading(false);
  }, [symbol]);

  useEffect(() => {
    setLoading(true);
    setDepth(null);
    fetchDepth();
    const interval = setInterval(fetchDepth, REFRESH_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [fetchDepth]);

  if (loading) {
    return (
      <div className="glass-card p-4">
        <div className="flex items-center gap-2 mb-3">
          <Layers className="h-4 w-4 text-accent" />
          <h2 className="text-sm font-semibold text-text-primary">Order Book Depth</h2>
        </div>
        <div className="flex items-center justify-center h-[280px]">
          <RefreshCw className="h-5 w-5 text-text-secondary animate-spin" />
        </div>
      </div>
    );
  }

  if (!depth || (depth.bids.length === 0 && depth.asks.length === 0)) {
    return (
      <div className="glass-card p-4">
        <div className="flex items-center gap-2 mb-3">
          <Layers className="h-4 w-4 text-accent" />
          <h2 className="text-sm font-semibold text-text-primary">Order Book Depth</h2>
        </div>
        <div className="flex items-center justify-center h-[280px] text-text-secondary text-sm">
          No order book data available
        </div>
      </div>
    );
  }

  return (
    <div className="glass-card p-4">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Layers className="h-4 w-4 text-accent" />
          <h2 className="text-sm font-semibold text-text-primary">Order Book Depth</h2>
          <span className="text-[10px] text-text-secondary bg-surface-light px-1.5 py-0.5 rounded">
            4 Exchanges
          </span>
        </div>
        <SpreadBadge spread={depth.spread} spreadPct={depth.spreadPct} />
      </div>

      <DepthChartSvg
        depth={depth}
        svgRef={svgRef}
        hoveredLevel={hoveredLevel}
        onHover={setHoveredLevel}
      />

      <DepthStats depth={depth} />
    </div>
  );
}

// --- SVG Depth Chart ---

interface DepthChartSvgProps {
  depth: OrderBookDepth;
  svgRef: React.RefObject<SVGSVGElement | null>;
  hoveredLevel: { side: 'bid' | 'ask'; index: number } | null;
  onHover: (level: { side: 'bid' | 'ask'; index: number } | null) => void;
}

function DepthChartSvg({ depth, svgRef, hoveredLevel, onHover }: DepthChartSvgProps) {
  const width = 800;
  const height = 260;
  const padding = { top: 20, right: 60, bottom: 30, left: 60 };
  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;

  // Build cumulative volumes
  const bidCumulative = buildCumulative(depth.bids, 'desc');
  const askCumulative = buildCumulative(depth.asks, 'asc');

  if (bidCumulative.length === 0 && askCumulative.length === 0) return null;

  // Price range: from lowest bid to highest ask
  const bidPrices = bidCumulative.map(l => l.price);
  const askPrices = askCumulative.map(l => l.price);
  const minPrice = bidPrices.length > 0 ? Math.min(...bidPrices) : depth.bestAsk * 0.95;
  const maxPrice = askPrices.length > 0 ? Math.max(...askPrices) : depth.bestBid * 1.05;
  const priceRange = maxPrice - minPrice || 1;

  // Volume range
  const maxCumVol = Math.max(
    ...bidCumulative.map(l => l.cumUsd),
    ...askCumulative.map(l => l.cumUsd),
    1
  );

  // Average volume per level for wall detection
  const allLevels = [...depth.bids, ...depth.asks];
  const avgUsd = allLevels.reduce((s, l) => s + l.totalUsd, 0) / (allLevels.length || 1);
  const wallThreshold = avgUsd * 2;

  const priceToX = (price: number) => padding.left + ((price - minPrice) / priceRange) * chartWidth;
  const volToY = (vol: number) => padding.top + chartHeight - (vol / maxCumVol) * chartHeight;

  // Build SVG paths
  const bidPath = buildAreaPath(bidCumulative, priceToX, volToY, padding.top + chartHeight);
  const askPath = buildAreaPath(askCumulative, priceToX, volToY, padding.top + chartHeight);
  const bidLine = buildLinePath(bidCumulative, priceToX, volToY);
  const askLine = buildLinePath(askCumulative, priceToX, volToY);

  // Mid price marker
  const midPrice = (depth.bestBid + depth.bestAsk) / 2;
  const midX = priceToX(midPrice);

  // Price axis ticks
  const priceTicks = generateTicks(minPrice, maxPrice, 6);
  // Volume axis ticks
  const volTicks = generateTicks(0, maxCumVol, 4);

  // Tooltip data
  const tooltipData = hoveredLevel
    ? hoveredLevel.side === 'bid'
      ? bidCumulative[hoveredLevel.index]
      : askCumulative[hoveredLevel.index]
    : null;

  return (
    <div className="relative">
      <svg
        ref={svgRef}
        viewBox={`0 0 ${width} ${height}`}
        className="w-full"
        onMouseLeave={() => onHover(null)}
      >
        <defs>
          <linearGradient id="bidGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#10b981" stopOpacity="0.4" />
            <stop offset="100%" stopColor="#10b981" stopOpacity="0.05" />
          </linearGradient>
          <linearGradient id="askGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#ef4444" stopOpacity="0.4" />
            <stop offset="100%" stopColor="#ef4444" stopOpacity="0.05" />
          </linearGradient>
        </defs>

        {/* Grid lines */}
        {volTicks.map((v, i) => (
          <line
            key={`hg-${i}`}
            x1={padding.left}
            y1={volToY(v)}
            x2={width - padding.right}
            y2={volToY(v)}
            stroke="#1e293b"
            strokeWidth="1"
          />
        ))}

        {/* Bid area */}
        <path d={bidPath} fill="url(#bidGrad)" />
        <path d={bidLine} fill="none" stroke="#10b981" strokeWidth="2" />

        {/* Ask area */}
        <path d={askPath} fill="url(#askGrad)" />
        <path d={askLine} fill="none" stroke="#ef4444" strokeWidth="2" />

        {/* Wall highlights — bright circles on levels > 2x average */}
        {bidCumulative.map((level, i) => {
          if (level.totalUsd <= wallThreshold) return null;
          return (
            <circle
              key={`bw-${i}`}
              cx={priceToX(level.price)}
              cy={volToY(level.cumUsd)}
              r="4"
              fill="#10b981"
              opacity="0.8"
            />
          );
        })}
        {askCumulative.map((level, i) => {
          if (level.totalUsd <= wallThreshold) return null;
          return (
            <circle
              key={`aw-${i}`}
              cx={priceToX(level.price)}
              cy={volToY(level.cumUsd)}
              r="4"
              fill="#ef4444"
              opacity="0.8"
            />
          );
        })}

        {/* Mid price marker */}
        <line
          x1={midX}
          y1={padding.top}
          x2={midX}
          y2={padding.top + chartHeight}
          stroke="#06b6d4"
          strokeWidth="1"
          strokeDasharray="4,3"
        />
        <text
          x={midX}
          y={padding.top - 6}
          textAnchor="middle"
          fill="#06b6d4"
          fontSize="10"
          fontFamily="monospace"
        >
          {formatPrice(midPrice)}
        </text>

        {/* Price axis */}
        {priceTicks.map((p, i) => (
          <text
            key={`pt-${i}`}
            x={priceToX(p)}
            y={height - 8}
            textAnchor="middle"
            fill="#64748b"
            fontSize="9"
            fontFamily="monospace"
          >
            {formatAxisPrice(p)}
          </text>
        ))}

        {/* Volume axis */}
        {volTicks.map((v, i) => (
          <text
            key={`vt-${i}`}
            x={padding.left - 6}
            y={volToY(v) + 3}
            textAnchor="end"
            fill="#64748b"
            fontSize="9"
            fontFamily="monospace"
          >
            {formatAxisVolume(v)}
          </text>
        ))}

        {/* Interactive hover zones for bids */}
        {bidCumulative.map((level, i) => (
          <rect
            key={`bh-${i}`}
            x={priceToX(level.price) - 4}
            y={padding.top}
            width={8}
            height={chartHeight}
            fill="transparent"
            onMouseEnter={() => onHover({ side: 'bid', index: i })}
          />
        ))}

        {/* Interactive hover zones for asks */}
        {askCumulative.map((level, i) => (
          <rect
            key={`ah-${i}`}
            x={priceToX(level.price) - 4}
            y={padding.top}
            width={8}
            height={chartHeight}
            fill="transparent"
            onMouseEnter={() => onHover({ side: 'ask', index: i })}
          />
        ))}

        {/* Hover crosshair */}
        {tooltipData && (
          <>
            <line
              x1={priceToX(tooltipData.price)}
              y1={padding.top}
              x2={priceToX(tooltipData.price)}
              y2={padding.top + chartHeight}
              stroke="#94a3b8"
              strokeWidth="1"
              strokeDasharray="2,2"
              opacity="0.5"
            />
            <circle
              cx={priceToX(tooltipData.price)}
              cy={volToY(tooltipData.cumUsd)}
              r="5"
              fill={hoveredLevel?.side === 'bid' ? '#10b981' : '#ef4444'}
              stroke="#fff"
              strokeWidth="1.5"
            />
          </>
        )}
      </svg>

      {/* Tooltip overlay */}
      {tooltipData && hoveredLevel && (
        <DepthTooltip
          level={tooltipData}
          side={hoveredLevel.side}
          x={priceToX(tooltipData.price)}
          svgWidth={width}
        />
      )}
    </div>
  );
}

// --- Stats Bar ---

function DepthStats({ depth }: { depth: OrderBookDepth }) {
  const imbalanceColor = depth.bidAskImbalance > 10
    ? 'text-gain'
    : depth.bidAskImbalance < -10
      ? 'text-loss'
      : 'text-text-secondary';

  const imbalanceLabel = depth.bidAskImbalance > 10
    ? 'Buy pressure'
    : depth.bidAskImbalance < -10
      ? 'Sell pressure'
      : 'Balanced';

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-3 pt-3 border-t border-surface-border">
      <StatItem label="Best Bid" value={formatPrice(depth.bestBid)} color="text-gain" />
      <StatItem label="Best Ask" value={formatPrice(depth.bestAsk)} color="text-loss" />
      <StatItem label="Bid Volume" value={formatLargeNumber(depth.totalBidVolume)} color="text-gain" />
      <StatItem label="Ask Volume" value={formatLargeNumber(depth.totalAskVolume)} color="text-loss" />
      <div className="col-span-2 sm:col-span-4">
        <div className="flex items-center gap-2">
          <span className="text-xs text-text-secondary">Imbalance</span>
          <div className="flex-1 h-2 bg-surface-light rounded-full overflow-hidden relative">
            <div
              className="absolute top-0 h-full bg-gain/60 rounded-l-full"
              style={{ left: 0, width: `${Math.max(0, 50 + depth.bidAskImbalance / 2)}%` }}
            />
            <div
              className="absolute top-0 h-full bg-loss/60 rounded-r-full"
              style={{ right: 0, width: `${Math.max(0, 50 - depth.bidAskImbalance / 2)}%` }}
            />
            <div
              className="absolute top-0 w-0.5 h-full bg-text-primary"
              style={{ left: '50%' }}
            />
          </div>
          <span className={`text-xs font-mono font-medium ${imbalanceColor}`}>
            {depth.bidAskImbalance > 0 ? '+' : ''}{depth.bidAskImbalance.toFixed(1)}%
          </span>
          <span className={`text-[10px] ${imbalanceColor}`}>{imbalanceLabel}</span>
        </div>
      </div>
    </div>
  );
}

function SpreadBadge({ spread, spreadPct }: { spread: number; spreadPct: number }) {
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="text-text-secondary">Spread:</span>
      <span className="font-mono text-accent">{formatPrice(spread)}</span>
      <span className="font-mono text-text-secondary">({spreadPct.toFixed(4)}%)</span>
    </div>
  );
}

function StatItem({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div>
      <p className="text-xs text-text-secondary">{label}</p>
      <p className={`text-sm font-mono font-medium ${color}`}>{value}</p>
    </div>
  );
}

// --- Tooltip ---

interface CumulativeLevel {
  price: number;
  quantity: number;
  totalUsd: number;
  cumUsd: number;
  exchangeCount: number;
}

function DepthTooltip({ level, side, x, svgWidth }: {
  level: CumulativeLevel;
  side: 'bid' | 'ask';
  x: number;
  svgWidth: number;
}) {
  const isLeft = x < svgWidth / 2;

  return (
    <div
      className="absolute top-2 pointer-events-none z-10 bg-surface border border-surface-border rounded-lg p-2 shadow-lg text-xs"
      style={{ [isLeft ? 'left' : 'right']: '12px' }}
    >
      <div className="flex items-center gap-1.5 mb-1">
        <span className={`w-2 h-2 rounded-full ${side === 'bid' ? 'bg-gain' : 'bg-loss'}`} />
        <span className="text-text-primary font-medium">{side === 'bid' ? 'Bid' : 'Ask'}</span>
      </div>
      <div className="space-y-0.5 text-text-secondary">
        <div className="flex justify-between gap-4">
          <span>Price:</span>
          <span className="font-mono text-text-primary">{formatPrice(level.price)}</span>
        </div>
        <div className="flex justify-between gap-4">
          <span>Size:</span>
          <span className="font-mono text-text-primary">{level.quantity.toFixed(4)}</span>
        </div>
        <div className="flex justify-between gap-4">
          <span>Level USD:</span>
          <span className="font-mono text-text-primary">{formatLargeNumber(level.totalUsd)}</span>
        </div>
        <div className="flex justify-between gap-4">
          <span>Cumulative:</span>
          <span className="font-mono text-text-primary">{formatLargeNumber(level.cumUsd)}</span>
        </div>
        <div className="flex justify-between gap-4">
          <span>Exchanges:</span>
          <span className="font-mono text-text-primary">{level.exchangeCount}</span>
        </div>
      </div>
    </div>
  );
}

// --- Helpers ---

function buildCumulative(levels: PriceLevelData[], direction: 'asc' | 'desc'): CumulativeLevel[] {
  if (levels.length === 0) return [];

  const sorted = [...levels].sort((a, b) =>
    direction === 'desc' ? b.price - a.price : a.price - b.price
  );

  let cumUsd = 0;
  return sorted.map(l => {
    cumUsd += l.totalUsd;
    return {
      price: l.price,
      quantity: l.quantity,
      totalUsd: l.totalUsd,
      cumUsd,
      exchangeCount: l.exchangeCount,
    };
  });
}

function buildAreaPath(
  levels: CumulativeLevel[],
  priceToX: (p: number) => number,
  volToY: (v: number) => number,
  baselineY: number
): string {
  if (levels.length === 0) return '';

  let d = `M ${priceToX(levels[0].price)} ${baselineY}`;

  for (let i = 0; i < levels.length; i++) {
    const x = priceToX(levels[i].price);
    const y = volToY(levels[i].cumUsd);
    if (i > 0) {
      // Stepped: horizontal then vertical
      d += ` L ${x} ${volToY(levels[i - 1].cumUsd)}`;
    }
    d += ` L ${x} ${y}`;
  }

  // Close back to baseline
  d += ` L ${priceToX(levels[levels.length - 1].price)} ${baselineY} Z`;
  return d;
}

function buildLinePath(
  levels: CumulativeLevel[],
  priceToX: (p: number) => number,
  volToY: (v: number) => number
): string {
  if (levels.length === 0) return '';

  let d = `M ${priceToX(levels[0].price)} ${volToY(levels[0].cumUsd)}`;

  for (let i = 1; i < levels.length; i++) {
    const x = priceToX(levels[i].price);
    // Stepped: horizontal then vertical
    d += ` L ${x} ${volToY(levels[i - 1].cumUsd)}`;
    d += ` L ${x} ${volToY(levels[i].cumUsd)}`;
  }

  return d;
}

function generateTicks(min: number, max: number, count: number): number[] {
  const step = (max - min) / count;
  const ticks: number[] = [];
  for (let i = 1; i < count; i++) {
    ticks.push(min + step * i);
  }
  return ticks;
}

function formatAxisPrice(price: number): string {
  if (price >= 10000) return `${(price / 1000).toFixed(1)}K`;
  if (price >= 1000) return price.toFixed(0);
  if (price >= 1) return price.toFixed(2);
  return price.toFixed(4);
}

function formatAxisVolume(vol: number): string {
  if (vol >= 1e6) return `${(vol / 1e6).toFixed(1)}M`;
  if (vol >= 1e3) return `${(vol / 1e3).toFixed(0)}K`;
  return vol.toFixed(0);
}
