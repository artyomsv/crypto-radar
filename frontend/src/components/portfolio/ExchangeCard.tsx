import type { ExchangeAccount, UpdateAccountRequest } from '@/types';
import { useExecutionStream } from '@/hooks/useExecutionStream';
import { ExchangeCardHeader } from './ExchangeCardHeader';
import { EquitySummary } from './EquitySummary';

interface PatchResult {
  success: boolean;
  error?: string;
}

interface Props {
  account: ExchangeAccount;
  onPatch: (id: number, fields: UpdateAccountRequest) => Promise<PatchResult>;
}

export function ExchangeCard({ account, onPatch }: Props) {
  const stream = useExecutionStream(account.id);

  const handleAutoTrade = () => {
    // Task 8 will gate first-time enable with FirstTimeAutoTradeModal.
    onPatch(account.id, { autoTradeEnabled: !account.autoTradeEnabled });
  };

  const handleKillSwitch = () => {
    onPatch(account.id, { killSwitch: !account.killSwitch });
  };

  const handleSettings = () => {
    // Wired in Task 12.
    console.log('settings panel — Task 12');
  };

  return (
    <div className="rounded-lg border border-[#1c1f27] bg-[#141820] p-4">
      <ExchangeCardHeader
        account={account}
        connectionState={stream.connectionState}
        secondsSinceUpdate={stream.secondsSinceUpdate}
        onAutoTradeToggle={handleAutoTrade}
        onKillSwitchClick={handleKillSwitch}
        onSettingsClick={handleSettings}
      />
      <EquitySummary wallet={stream.wallet} />
      <div className="mb-2 text-[11px] uppercase tracking-wide text-gray-500">Open positions</div>
      <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">
        (OpenPositionsTable renders here in Task 5)
      </div>
      <div className="mt-4 mb-2 text-[11px] uppercase tracking-wide text-gray-500">Recent closed (last 24h)</div>
      <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">
        (RecentTradesList renders here in Task 10)
      </div>
    </div>
  );
}
