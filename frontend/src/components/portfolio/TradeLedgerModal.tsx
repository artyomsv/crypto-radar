import { useEffect, useState } from 'react';
import { X, ChevronLeft, ChevronRight } from 'lucide-react';
import { api } from '@/lib/api';
import type { ExecutionTrade, TradeHistoryPage } from '@/types';

const PAGE_SIZE = 25;

interface Props {
  accountId: number;
  onClose: () => void;
  onShowChart: (trade: ExecutionTrade) => void;
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
    case 'TRAIL_STOP':
      return { code: 'TRAIL', bg: 'rgba(247,166,0,0.15)', fg: '#f7a600' };
    case 'STOP':
    case 'INITIAL_STOP':
      return { code: 'STOP', bg: 'rgba(239,68,68,0.15)', fg: '#ef4444' };
    case 'EXPIRED':
      return { code: 'EXPIRED', bg: 'rgba(136,136,136,0.15)', fg: '#888' };
    case 'FLIP_CLOSE':
      return { code: 'FLIP', bg: 'rgba(26,115,232,0.15)', fg: '#1a73e8' };
    case 'STAGNATION':
      return { code: 'STAGNANT', bg: 'rgba(168,85,247,0.15)', fg: '#a855f7' };
    case 'MANUAL':
      return { code: 'MANUAL', bg: 'rgba(139,92,246,0.15)', fg: '#8b5cf6' };
    case 'KILL':
      return { code: 'KILL', bg: 'rgba(239,68,68,0.15)', fg: '#ef4444' };
    default:
      return { code: exitReason ?? '—', bg: 'rgba(136,136,136,0.15)', fg: '#888' };
  }
}

function formatClosedAt(closedAt: string | null): string {
  if (!closedAt) return '—';
  const d = new Date(closedAt);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

export function TradeLedgerModal({ accountId, onClose, onShowChart }: Props) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<TradeHistoryPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', esc);
    return () => document.removeEventListener('keydown', esc);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    (async () => {
      const result = await api.execution.getTradeHistory(accountId, page, PAGE_SIZE);
      if (cancelled) return;
      if (result == null) {
        setError('Could not load trade history.');
        setData(null);
      } else {
        setData(result);
      }
      setLoading(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [accountId, page]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
  const canPrev = page > 0;
  const canNext = data != null && page + 1 < totalPages;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={onClose}
    >
      <div
        className="flex max-h-[85vh] w-[860px] flex-col overflow-hidden rounded-lg border border-[#2a3040] bg-[#141820]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex shrink-0 items-center justify-between border-b border-[#1c1f27] px-5 py-3">
          <div>
            <div className="text-sm font-semibold text-white">Trade history</div>
            <div className="text-[10px] text-gray-400">
              {data ? `${data.total} closed trades` : 'Loading…'}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-white"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto">
          {loading && (
            <div className="px-5 py-12 text-center text-[11px] text-gray-500">Loading…</div>
          )}
          {error && !loading && (
            <div className="px-5 py-12 text-center text-[11px] text-[#ef4444]">{error}</div>
          )}
          {!loading && data && data.items.length === 0 && (
            <div className="px-5 py-12 text-center text-[11px] text-gray-500">
              No closed trades yet.
            </div>
          )}
          {!loading && data && data.items.length > 0 && (
            <table className="w-full text-[11px] text-white">
              <thead className="sticky top-0 bg-[#1a1f2a] text-[10px] uppercase tracking-wide text-gray-500">
                <tr>
                  <th className="px-4 py-2 text-left">Closed</th>
                  <th className="px-2 py-2 text-left">Symbol</th>
                  <th className="px-2 py-2 text-left">Side</th>
                  <th className="px-2 py-2 text-left">Strategy</th>
                  <th className="px-2 py-2 text-left">Exit</th>
                  <th className="px-2 py-2 text-right">R</th>
                  <th className="px-2 py-2 text-right">PnL</th>
                  <th className="px-2 py-2 text-right">Fees</th>
                  <th className="px-4 py-2 text-right">Chart</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((t) => {
                  const badge = badgeFor(t.exitReason);
                  const r = t.realizedRMultiple;
                  const pnl = t.realizedPnlUsdt;
                  const fees = t.feesUsdt;
                  return (
                    <tr key={t.id} className="border-t border-[#1c1f27]">
                      <td className="px-4 py-2 text-gray-300">{formatClosedAt(t.closedAt)}</td>
                      <td className="px-2 py-2 font-medium">{t.symbol}</td>
                      <td className={`px-2 py-2 ${t.direction === 'LONG' ? 'text-[#4ade80]' : 'text-[#ef4444]'}`}>
                        {t.direction}
                      </td>
                      <td className="px-2 py-2 text-gray-400">{t.strategy ?? '—'}</td>
                      <td className="px-2 py-2">
                        <span
                          className="rounded px-1.5 py-[1px] text-[9px] font-semibold"
                          style={{ backgroundColor: badge.bg, color: badge.fg }}
                        >
                          {badge.code}
                        </span>
                      </td>
                      <td className={`px-2 py-2 text-right font-mono ${r != null && r >= 0 ? 'text-[#4ade80]' : r != null ? 'text-[#ef4444]' : 'text-gray-500'}`}>
                        {r != null ? `${r >= 0 ? '+' : ''}${r.toFixed(2)}` : '—'}
                      </td>
                      <td className={`px-2 py-2 text-right font-mono ${pnl != null && pnl >= 0 ? 'text-[#4ade80]' : pnl != null ? 'text-[#ef4444]' : 'text-gray-500'}`}>
                        {pnl != null ? `${pnl >= 0 ? '+' : ''}$${Math.abs(pnl).toFixed(2)}` : '—'}
                      </td>
                      <td className="px-2 py-2 text-right font-mono text-gray-500">
                        {fees != null ? `$${fees.toFixed(2)}` : '—'}
                      </td>
                      <td className="px-4 py-2 text-right">
                        <button
                          type="button"
                          onClick={() => onShowChart(t)}
                          className="rounded border border-[#2a3040] px-2 py-[2px] text-[10px] text-gray-300 hover:border-[#1a73e8] hover:text-white"
                        >
                          view
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="flex shrink-0 items-center justify-between border-t border-[#1c1f27] px-5 py-3 text-[11px] text-gray-400">
          <span>
            {data
              ? `Page ${page + 1} of ${totalPages}`
              : ''}
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={!canPrev || loading}
              className="flex items-center gap-1 rounded border border-[#2a3040] px-2 py-1 disabled:cursor-not-allowed disabled:opacity-40 hover:border-[#1a73e8] hover:text-white"
            >
              <ChevronLeft size={12} /> Prev
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              disabled={!canNext || loading}
              className="flex items-center gap-1 rounded border border-[#2a3040] px-2 py-1 disabled:cursor-not-allowed disabled:opacity-40 hover:border-[#1a73e8] hover:text-white"
            >
              Next <ChevronRight size={12} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
