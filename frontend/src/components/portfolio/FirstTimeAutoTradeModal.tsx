import { useEffect } from 'react';
import { X } from 'lucide-react';
import type { ExchangeAccount } from '@/types';

interface Props {
  account: ExchangeAccount;
  onConfirm: () => void;
  onCancel: () => void;
}

export function FirstTimeAutoTradeModal({ account, onConfirm, onCancel }: Props) {
  useEffect(() => {
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', esc);
    return () => document.removeEventListener('keydown', esc);
  }, [onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={onCancel}
    >
      <div
        className="w-[460px] rounded-lg border border-[#2a3040] bg-[#141820] p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <div className="text-sm font-semibold text-white">Enable live trading?</div>
          <button
            type="button"
            onClick={onCancel}
            className="text-gray-400 hover:text-white"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>
        <div className="mb-4 text-[12px] leading-relaxed text-gray-300">
          You're about to enable auto-trade on{' '}
          <span className="font-semibold text-white">Bybit {account.environment}</span>.
          STRONG_BUY / STRONG_SELL signals will open real orders with real money.
        </div>
        <div className="mb-4 rounded bg-[#0f1116] p-3 text-[11px]">
          <KV label="Risk per trade" value={`${account.riskPercent}% of equity`} />
          <KV label="Default leverage" value={`${account.defaultLeverage}×`} />
          <KV label="Max concurrent" value={`${account.maxConcurrentPositions}`} />
          <KV label="Daily loss halt" value={`${account.maxDailyLossPercent}%`} />
          <KV label="Signal max age" value={`${account.signalAgeSeconds}s`} />
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="flex-1 rounded bg-[#222] px-3 py-2 text-xs text-gray-300 hover:bg-[#2a2f38]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            className="flex-1 rounded bg-[#4ade80] px-3 py-2 text-xs font-semibold text-black hover:bg-[#6ee498]"
          >
            I understand, activate
          </button>
        </div>
      </div>
    </div>
  );
}

interface KVProps {
  label: string;
  value: string;
}

function KV({ label, value }: KVProps) {
  return (
    <div className="flex justify-between border-b border-[#1c1f27] py-1 text-white last:border-0">
      <span className="text-gray-400">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  );
}
