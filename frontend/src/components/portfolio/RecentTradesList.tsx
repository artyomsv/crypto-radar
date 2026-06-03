import type { ExecutionTrade } from '@/types';

interface Props {
  trades: ExecutionTrade[];
  onShowAll: () => void;
}

interface ExitBadge {
  code: string;
  bg: string;
  fg: string;
}

function badgeFor(exitReason: string | null): ExitBadge {
  switch (exitReason) {
    case 'TARGET':
      return { code: 'TARGET', bg: 'rgba(74,222,128,0.15)', fg: '#4ade80' };
    case 'TRAIL':
    case 'DYNAMIC_STOP':
      return { code: 'TRAIL', bg: 'rgba(247,166,0,0.15)', fg: '#f7a600' };
    case 'STOP':
    case 'INITIAL_STOP':
      return { code: 'STOP', bg: 'rgba(239,68,68,0.15)', fg: '#ef4444' };
    case 'EXPIRED':
      return { code: 'EXPIRED', bg: 'rgba(136,136,136,0.15)', fg: '#888888' };
    case 'FLIP_CLOSE':
      return { code: 'FLIP', bg: 'rgba(26,115,232,0.15)', fg: '#1a73e8' };
    case 'MANUAL':
      return { code: 'MANUAL', bg: 'rgba(139,92,246,0.15)', fg: '#8b5cf6' };
    case 'KILL':
      return { code: 'KILL', bg: 'rgba(239,68,68,0.15)', fg: '#ef4444' };
    default:
      return { code: exitReason ?? '—', bg: 'rgba(136,136,136,0.15)', fg: '#888888' };
  }
}

const ONE_DAY_MS = 24 * 60 * 60 * 1000;
const MAX_ROWS = 10;

export function RecentTradesList({ trades, onShowAll }: Props) {
  const cutoff = Date.now() - ONE_DAY_MS;
  const recent = trades
    .filter((t) => t.closedAt != null && new Date(t.closedAt).getTime() >= cutoff)
    .slice(0, MAX_ROWS);

  if (recent.length === 0) {
    return (
      <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">
        <div>No closed trades in the last 24 hours.</div>
        <button
          type="button"
          onClick={onShowAll}
          className="mt-2 text-gray-400 hover:text-gray-200"
        >
          see full history →
        </button>
      </div>
    );
  }

  return (
    <div className="rounded bg-[#141820] p-3">
      {recent.map((t, i) => {
        const badge = badgeFor(t.exitReason);
        const r = t.realizedRMultiple;
        const pnl = t.realizedPnlUsdt;
        return (
          <div
            key={t.id}
            className={`flex items-center justify-between py-1.5 text-[11px] text-white ${
              i > 0 ? 'border-t border-[#1c1f27]' : ''
            }`}
          >
            <span>
              {t.symbol} {t.direction}
            </span>
            <span>
              <span
                className="mr-2 rounded px-1.5 py-[1px] text-[9px] font-semibold"
                style={{ backgroundColor: badge.bg, color: badge.fg }}
              >
                {badge.code}
              </span>
              {r != null && (
                <span className={r >= 0 ? 'text-[#4ade80]' : 'text-[#ef4444]'}>
                  {r >= 0 ? '+' : ''}
                  {r.toFixed(1)}R
                </span>
              )}
              {pnl != null && (
                <span className={`ml-1 ${pnl >= 0 ? 'text-[#4ade80]' : 'text-[#ef4444]'}`}>
                  · {pnl >= 0 ? '+' : ''}${Math.abs(pnl).toFixed(0)}
                </span>
              )}
            </span>
          </div>
        );
      })}
      <button
        type="button"
        onClick={onShowAll}
        className="mt-2 w-full rounded border-t border-[#1c1f27] pt-2 text-[10px] text-gray-500 hover:text-gray-300"
      >
        see all →
      </button>
    </div>
  );
}
