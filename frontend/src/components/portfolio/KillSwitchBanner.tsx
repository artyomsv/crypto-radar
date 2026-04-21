interface Props {
  onDisarm: () => void;
}

export function KillSwitchBanner({ onDisarm }: Props) {
  return (
    <div className="-mx-4 -mt-4 mb-4 flex items-center justify-between rounded-t-lg bg-[#3a1a1a] px-4 py-2.5 text-[11px] font-semibold text-[#ef4444]">
      <span>KILL SWITCH ACTIVE — no new positions</span>
      <button
        type="button"
        onClick={onDisarm}
        className="rounded bg-[#ef4444] px-3 py-1 text-[10px] font-semibold text-white hover:bg-[#dc2626]"
      >
        DISARM
      </button>
    </div>
  );
}
