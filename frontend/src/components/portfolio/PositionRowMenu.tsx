import { useEffect, useRef, useState } from 'react';
import type { ExecutionPosition } from '@/types';

interface Props {
  position: ExecutionPosition;
  anchor: HTMLElement;
  onClose: () => void;
  onViewChart: () => void;
  onViewWhy: () => void;
  onCloseAtMarket: () => void;
}

export function PositionRowMenu({
  position,
  anchor,
  onClose,
  onViewChart,
  onViewWhy,
  onCloseAtMarket,
}: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node) && !anchor.contains(e.target as Node)) {
        onClose();
      }
    };
    const escHandler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', handler);
    document.addEventListener('keydown', escHandler);
    return () => {
      document.removeEventListener('mousedown', handler);
      document.removeEventListener('keydown', escHandler);
    };
  }, [anchor, onClose]);

  const rect = anchor.getBoundingClientRect();
  const style: React.CSSProperties = {
    position: 'fixed',
    top: rect.bottom + 4,
    left: Math.max(8, rect.right - 160),
    width: 160,
  };

  return (
    <div
      ref={ref}
      style={style}
      className="z-50 rounded border border-[#2a3040] bg-[#0a0d14] py-1 shadow-lg"
    >
      <button
        type="button"
        onClick={() => {
          onViewChart();
          onClose();
        }}
        className="block w-full px-3 py-1.5 text-left text-[11px] text-gray-300 hover:bg-[#141820]"
      >
        View in chart
      </button>
      <button
        type="button"
        onClick={() => {
          onViewWhy();
          onClose();
        }}
        className="block w-full px-3 py-1.5 text-left text-[11px] text-gray-300 hover:bg-[#141820]"
      >
        Why this trade?
      </button>
      {confirming ? (
        <div className="border-t border-[#1c1f27] pt-2">
          <div className="px-3 text-[10px] text-gray-400">
            Close {position.symbol} {position.direction} @ market?
          </div>
          <div className="mt-2 flex gap-1 px-2 pb-1">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 rounded bg-[#222] px-2 py-1 text-[10px] text-gray-300"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                onCloseAtMarket();
                onClose();
              }}
              className="flex-1 rounded bg-[#ef4444] px-2 py-1 text-[10px] font-semibold text-white"
            >
              Close
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          className="mt-1 block w-full border-t border-[#1c1f27] px-3 py-1.5 text-left text-[11px] text-[#ef4444] hover:bg-[#141820]"
        >
          Close at market
        </button>
      )}
    </div>
  );
}
