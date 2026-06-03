import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { RealizedVolReading } from '@/types';
import { cn } from '@/lib/utils';

const UNDERLYINGS = ['BTC', 'ETH', 'SOL', 'XAUT', 'XRP', 'MNT', 'DOGE'];

interface VolRow {
  underlying: string;
  rv7?: number | null;
  rv14?: number | null;
  rv30?: number | null;
  error?: string;
}

export function VolGapPanel() {
  const [rows, setRows] = useState<VolRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      const results = await Promise.all(
        UNDERLYINGS.map(async (u) => {
          const [r7, r14, r30] = await Promise.all([
            api.getOptionsRealizedVol(u, 7),
            api.getOptionsRealizedVol(u, 14),
            api.getOptionsRealizedVol(u, 30),
          ]);
          return {
            underlying: u,
            rv7: (r7 as RealizedVolReading | null)?.annualizedPct ?? null,
            rv14: (r14 as RealizedVolReading | null)?.annualizedPct ?? null,
            rv30: (r30 as RealizedVolReading | null)?.annualizedPct ?? null,
          };
        }),
      );
      if (!cancelled) {
        setRows(results);
        setLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return <div className="glass-card p-4 text-sm text-text-secondary">Loading realized vol…</div>;
  }

  return (
    <div className="glass-card p-4">
      <h3 className="text-xs font-semibold text-text-secondary uppercase tracking-wider mb-3 border-b border-surface-border pb-1">
        Realized Volatility (annualized %)
      </h3>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-text-secondary">
              <th className="text-left py-1">Underlying</th>
              <th className="text-right py-1">RV 7d</th>
              <th className="text-right py-1">RV 14d</th>
              <th className="text-right py-1">RV 30d</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.underlying} className="border-t border-surface-border/40">
                <td className="py-1 font-mono text-text-primary">{r.underlying}</td>
                <td className={cn('py-1 text-right font-mono', r.rv7 == null && 'text-text-secondary')}>{r.rv7?.toFixed(1) ?? '—'}</td>
                <td className={cn('py-1 text-right font-mono', r.rv14 == null && 'text-text-secondary')}>{r.rv14?.toFixed(1) ?? '—'}</td>
                <td className={cn('py-1 text-right font-mono', r.rv30 == null && 'text-text-secondary')}>{r.rv30?.toFixed(1) ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
