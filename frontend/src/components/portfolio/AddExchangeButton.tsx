import { Plus } from 'lucide-react';

interface Props {
  onClick: () => void;
}

export function AddExchangeButton({ onClick }: Props) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full cursor-pointer rounded-lg border border-dashed border-[#333] bg-[#141820] p-8 text-center transition hover:border-[#1a73e8]"
    >
      <div className="mx-auto mb-3 flex h-11 w-11 items-center justify-center rounded-full bg-[#1a73e8] text-white">
        <Plus size={22} />
      </div>
      <div className="mb-1 text-sm font-semibold text-white">Connect an exchange</div>
      <div className="mb-4 text-[10px] leading-relaxed text-gray-500">
        Mirror signal-service STRONG_BUY / STRONG_SELL<br />to real orders with native TP/SL + trailing stop.
      </div>
      <span className="inline-block rounded bg-[#1a73e8] px-4 py-1.5 text-xs font-semibold text-white">+ Add Bybit</span>
    </button>
  );
}
