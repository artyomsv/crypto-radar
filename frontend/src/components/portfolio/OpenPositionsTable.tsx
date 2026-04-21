import type { ExecutionPosition } from '@/types';
import { MoreHorizontal } from 'lucide-react';

interface Props {
  positions: ExecutionPosition[];
  livePrices: Record<string, number>;
  onRowMenu: (position: ExecutionPosition, anchor: HTMLElement) => void;
  onWhyClick: (position: ExecutionPosition) => void;
}

const GRID = 'grid-cols-[100px_60px_90px_90px_90px_90px_80px_1fr_24px]';

export function OpenPositionsTable({ positions, livePrices, onRowMenu, onWhyClick }: Props) {
  if (positions.length === 0) {
    return (
      <div className="rounded bg-[#0f1116] p-6 text-center text-[11px] text-gray-500">
        No open positions. Signal-driven entries appear here once the auto-trade toggle is armed.
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded bg-[#141820]">
      <div className={`grid ${GRID} bg-[#0f1116] px-3 py-2.5 text-[10px] uppercase tracking-wide text-gray-500`}>
        <div>Symbol</div>
        <div>Side</div>
        <div>Entry</div>
        <div>Current</div>
        <div>Stop</div>
        <div>Target</div>
        <div>P&amp;L</div>
        <div>Why</div>
        <div></div>
      </div>
      {positions.map((p) => (
        <PositionRow
          key={p.id}
          position={p}
          livePrice={livePrices[p.symbol]}
          onMenu={(anchor) => onRowMenu(p, anchor)}
          onWhy={() => onWhyClick(p)}
        />
      ))}
    </div>
  );
}

interface RowProps {
  position: ExecutionPosition;
  livePrice: number | undefined;
  onMenu: (anchor: HTMLElement) => void;
  onWhy: () => void;
}

interface DetectorBadge {
  code: string;
  bg: string;
}

function detectorBadgeFor(strategy: string | null): DetectorBadge {
  const s = (strategy ?? 'DIM').toLowerCase();
  if (s.includes('liquidity')) return { code: 'LS', bg: '#1a73e8' };
  if (s.includes('trend')) return { code: 'TC', bg: '#8b5cf6' };
  return { code: 'DIM', bg: '#555' };
}

function PositionRow({ position, livePrice, onMenu, onWhy }: RowProps) {
  const current = livePrice ?? position.entryPrice ?? 0;
  const isLong = position.direction === 'LONG';
  const entry = position.entryPrice ?? 0;
  const stop = position.dynamicStopPrice ?? position.stopPrice;
  const target = position.targetPrice;
  const riskDist = Math.abs(entry - position.stopPrice);
  const pnlDist = isLong ? current - entry : entry - current;
  const rMultiple = riskDist > 0 ? pnlDist / riskDist : 0;
  const pnlUsd = pnlDist * (position.qty ?? 0);

  const pnlColor = pnlDist > 0 ? 'text-[#4ade80]' : pnlDist < 0 ? 'text-[#ef4444]' : 'text-white';
  const sideColor = isLong ? 'bg-[#4ade80]/15 text-[#4ade80]' : 'bg-[#ef4444]/15 text-[#ef4444]';
  const badge = detectorBadgeFor(position.strategy);
  const trailActive =
    position.trailTriggeredAt != null &&
    position.dynamicStopPrice != null &&
    position.dynamicStopPrice !== position.stopPrice;

  return (
    <div className={`grid ${GRID} items-center border-t border-[#1c1f27] px-3 py-3 text-[11px] text-white`}>
      <div className="font-semibold">{position.symbol}</div>
      <div>
        <span className={`rounded px-2 py-[1px] text-[9px] font-semibold ${sideColor}`}>
          {position.direction}
        </span>
      </div>
      <div>{fmt(entry)}</div>
      <div>{fmt(current)}</div>
      <div className={trailActive ? 'text-[#f7a600]' : ''}>
        {fmt(stop)}
        {trailActive && (
          <span className="ml-1 text-[9px] text-gray-500">
            TRAIL +{position.trailHighestR.toFixed(1)}R
          </span>
        )}
      </div>
      <div>{fmt(target)}</div>
      <div className={`${pnlColor} font-semibold`}>
        {rMultiple >= 0 ? '+' : ''}
        {rMultiple.toFixed(1)}R · {pnlUsd >= 0 ? '+' : ''}${Math.abs(pnlUsd).toFixed(0)}
      </div>
      <div className="text-[10px]">
        <button
          type="button"
          onClick={onWhy}
          className="mr-1 rounded px-1.5 py-[1px] text-white"
          style={{ backgroundColor: badge.bg }}
        >
          {badge.code}
        </button>
        <span className="text-gray-500">alignment —</span>
      </div>
      <div className="text-right">
        <button
          type="button"
          onClick={(e) => onMenu(e.currentTarget)}
          aria-label="Row actions"
          className="text-gray-500 hover:text-white"
        >
          <MoreHorizontal size={14} />
        </button>
      </div>
    </div>
  );
}

function fmt(n: number): string {
  if (!Number.isFinite(n) || n === 0) return '—';
  const abs = Math.abs(n);
  if (abs >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  if (abs >= 1) return n.toFixed(2);
  return n.toFixed(4);
}
