import { Settings } from 'lucide-react';
import type { ExchangeAccount } from '@/types';
import { ConnectionIndicator } from './ConnectionIndicator';
import type { ConnectionState } from '@/hooks/useExecutionStream';

interface Props {
  account: ExchangeAccount;
  connectionState: ConnectionState;
  secondsSinceUpdate: number;
  onAutoTradeToggle: () => void;
  onKillSwitchClick: () => void;
  onSettingsClick: () => void;
}

export function ExchangeCardHeader({
  account,
  connectionState,
  secondsSinceUpdate,
  onAutoTradeToggle,
  onKillSwitchClick,
  onSettingsClick,
}: Props) {
  return (
    <div className="mb-4 flex items-center justify-between border-b border-[#1c1f27] pb-3">
      <div className="flex items-center gap-3">
        <div className="flex h-8 w-8 items-center justify-center rounded bg-[#f7a600] text-sm font-bold text-black">B</div>
        <div>
          <div className="text-sm font-semibold text-white">Bybit</div>
          <ConnectionIndicator
            state={connectionState}
            secondsSinceUpdate={secondsSinceUpdate}
            environment={account.environment}
          />
        </div>
      </div>
      <div className="flex items-center gap-2">
        <span className="text-[10px] text-gray-400">Auto-trade</span>
        <button
          type="button"
          onClick={onAutoTradeToggle}
          className={`relative h-[22px] w-10 rounded-full p-[2px] transition ${
            account.autoTradeEnabled ? 'bg-[#4ade80]' : 'bg-[#333]'
          }`}
          aria-label="Toggle auto-trade"
        >
          <div
            className="h-[18px] w-[18px] rounded-full bg-white transition"
            style={{ marginLeft: account.autoTradeEnabled ? 18 : 0 }}
          />
        </button>
        <button
          type="button"
          onClick={onKillSwitchClick}
          className={`rounded px-3 py-1.5 text-[11px] font-semibold ${
            account.killSwitch ? 'bg-[#4ade80] text-black' : 'bg-[#ef4444] text-white'
          }`}
        >
          {account.killSwitch ? 'DISARM' : 'KILL SWITCH'}
        </button>
        <button
          type="button"
          onClick={onSettingsClick}
          aria-label="Settings"
          className="flex items-center gap-1 rounded border border-[#333] bg-[#222] px-2 py-1.5 text-[11px] text-gray-300 hover:bg-[#2a2f38]"
        >
          <Settings size={12} />
        </button>
      </div>
    </div>
  );
}
