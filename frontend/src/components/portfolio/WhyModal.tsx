import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { api } from '@/lib/api';
import type { ExecutionPosition, WhyView } from '@/types';

interface Props {
  accountId: number;
  position: ExecutionPosition;
  onClose: () => void;
}

export function WhyModal({ accountId, position, onClose }: Props) {
  const [why, setWhy] = useState<WhyView | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const data = await api.execution.getWhy(accountId, position.id);
      if (!cancelled) {
        setWhy(data);
        setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [accountId, position.id]);

  useEffect(() => {
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', esc);
    return () => document.removeEventListener('keydown', esc);
  }, [onClose]);

  const riskDist = position.entryPrice != null ? Math.abs(position.entryPrice - position.stopPrice) : null;
  const rewardDist = position.entryPrice != null ? Math.abs(position.targetPrice - position.entryPrice) : null;
  const rr = riskDist != null && rewardDist != null && riskDist > 0 ? rewardDist / riskDist : null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={onClose}
    >
      <div
        className="max-h-[85vh] w-[560px] overflow-y-auto rounded-lg border border-[#2a3040] bg-[#141820] p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <div>
            <div className="text-sm font-semibold text-white">Why this trade?</div>
            <div className="text-[10px] text-gray-400">
              {position.symbol} {position.direction} · strategy: {position.strategy ?? 'dimension'} · signalId:{' '}
              {position.signalId ?? '—'}
            </div>
          </div>
          <button type="button" onClick={onClose} className="text-gray-400 hover:text-white" aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <Section title="Trade levels">
          <KVRow label="Entry" value={fmt(position.entryPrice)} />
          <KVRow label="Stop" value={fmt(position.stopPrice)} />
          <KVRow label="Target" value={fmt(position.targetPrice)} />
          <KVRow label="Qty" value={position.qty != null ? String(position.qty) : '—'} />
          <KVRow label="Leverage" value={position.leverage != null ? `${position.leverage}×` : '—'} />
          <KVRow label="R:R" value={rr != null ? `${rr.toFixed(2)}:1` : '—'} />
        </Section>

        <Section title="Trail state">
          <KVRow label="Current rung" value={`${position.trailHighestR.toFixed(1)}R`} />
          <KVRow label="Dynamic stop" value={fmt(position.dynamicStopPrice)} />
          <KVRow label="Trail activated" value={position.trailTriggeredAt ?? '—'} />
        </Section>

        <Section title="Signal context (from /why endpoint)">
          {loading && <div className="text-[11px] text-gray-500">Loading…</div>}
          {!loading && why && (
            <pre className="overflow-x-auto rounded bg-[#0f1116] p-3 text-[10px] text-gray-300">
{JSON.stringify(why.signalSnapshot, null, 2)}
            </pre>
          )}
          {!loading && !why && (
            <div className="text-[11px] text-gray-500">Could not load signal context.</div>
          )}
        </Section>
      </div>
    </div>
  );
}

interface SectionProps {
  title: string;
  children: React.ReactNode;
}

function Section({ title, children }: SectionProps) {
  return (
    <div className="mb-4">
      <div className="mb-2 text-[10px] uppercase tracking-wide text-gray-500">{title}</div>
      <div className="rounded bg-[#0f1116] p-3 text-[11px] text-white">{children}</div>
    </div>
  );
}

interface KVRowProps {
  label: string;
  value: string;
}

function KVRow({ label, value }: KVRowProps) {
  return (
    <div className="flex justify-between border-b border-[#1c1f27] py-1 last:border-0">
      <span className="text-gray-400">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  );
}

function fmt(n: number | null): string {
  if (n == null || !Number.isFinite(n)) return '—';
  const abs = Math.abs(n);
  if (abs >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  if (abs >= 1) return n.toFixed(2);
  return n.toFixed(4);
}
