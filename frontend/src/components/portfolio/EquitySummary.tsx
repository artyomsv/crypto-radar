import type { WalletSnapshot } from '@/types';

interface Props {
  wallet: WalletSnapshot | null;
}

export function EquitySummary({ wallet }: Props) {
  return (
    <div className="mb-4 grid grid-cols-5 gap-2.5">
      <Card label="Equity" value={fmtUsd(wallet?.equity)} />
      <Card label="Avail. margin" value={fmtUsd(wallet?.available)} />
      <Card label="Open P&L" value={fmtUsd(wallet?.openPnl)} colorize />
      <Card label="Today realized" value={fmtUsd(wallet?.todayRealized)} colorize />
      <Card label="Positions" value={wallet ? String(wallet.positionsOpen) : '—'} />
    </div>
  );
}

interface CardProps {
  label: string;
  value: string;
  colorize?: boolean;
}

function Card({ label, value, colorize = false }: CardProps) {
  const numeric = parseFloat(value.replace(/[^-0-9.]/g, ''));
  const color = colorize && !Number.isNaN(numeric)
    ? (numeric > 0 ? '#4ade80' : numeric < 0 ? '#ef4444' : '#ffffff')
    : '#ffffff';
  return (
    <div className="rounded bg-[#0f1116] p-3">
      <div className="text-[10px] uppercase text-gray-500">{label}</div>
      <div className="text-lg font-semibold" style={{ color }}>{value}</div>
    </div>
  );
}

function fmtUsd(n: number | null | undefined): string {
  if (n == null || Number.isNaN(n)) return '—';
  const sign = n > 0 ? '+' : '';
  return `${sign}$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
