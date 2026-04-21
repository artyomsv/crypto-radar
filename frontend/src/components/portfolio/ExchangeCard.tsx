import { useState } from 'react';
import type { ExchangeAccount, ExecutionPosition, UpdateAccountRequest } from '@/types';
import { useExecutionStream } from '@/hooks/useExecutionStream';
import { useWebSocket } from '@/hooks/useWebSocket';
import { api } from '@/lib/api';
import { ExchangeCardHeader } from './ExchangeCardHeader';
import { EquitySummary } from './EquitySummary';
import { OpenPositionsTable } from './OpenPositionsTable';
import { PositionRowMenu } from './PositionRowMenu';
import { WhyModal } from './WhyModal';

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
  const [livePrices, setLivePrices] = useState<Record<string, number>>({});
  const [rowMenu, setRowMenu] = useState<{ position: ExecutionPosition; anchor: HTMLElement } | null>(null);
  const [whyFor, setWhyFor] = useState<ExecutionPosition | null>(null);

  useWebSocket({
    onPrices: (prices: Array<{ symbol: string; price: number }>) => {
      if (!Array.isArray(prices)) return;
      setLivePrices((prev) => {
        const next = { ...prev };
        for (const p of prices) {
          if (p.symbol && p.price) next[p.symbol] = p.price;
        }
        return next;
      });
    },
  });

  const handleRowMenu = (position: ExecutionPosition, anchor: HTMLElement) => {
    setRowMenu({ position, anchor });
  };

  const handleWhy = (position: ExecutionPosition) => setWhyFor(position);

  const handleViewChart = (_position: ExecutionPosition) => {
    // Wired in Task 13 (existing TradeChartModal scaffold) or filed as tech-debt.
    console.log('chart modal — Task 13');
  };

  const handleCloseAtMarket = async (position: ExecutionPosition) => {
    const result = await api.execution.closeTrade(account.id, position.id);
    if (result.error) {
      alert(`Close failed: ${result.error}`);
      return;
    }
    await stream.refresh();
  };

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
      <OpenPositionsTable
        positions={stream.positions}
        livePrices={livePrices}
        onRowMenu={handleRowMenu}
        onWhyClick={handleWhy}
      />
      <div className="mt-4 mb-2 text-[11px] uppercase tracking-wide text-gray-500">Recent closed (last 24h)</div>
      <div className="rounded bg-[#141820] p-4 text-center text-[10px] text-gray-500">
        (RecentTradesList renders here in Task 10)
      </div>
      {rowMenu && (
        <PositionRowMenu
          position={rowMenu.position}
          anchor={rowMenu.anchor}
          onClose={() => setRowMenu(null)}
          onViewChart={() => handleViewChart(rowMenu.position)}
          onViewWhy={() => handleWhy(rowMenu.position)}
          onCloseAtMarket={() => handleCloseAtMarket(rowMenu.position)}
        />
      )}
      {whyFor && (
        <WhyModal
          accountId={account.id}
          position={whyFor}
          onClose={() => setWhyFor(null)}
        />
      )}
    </div>
  );
}
