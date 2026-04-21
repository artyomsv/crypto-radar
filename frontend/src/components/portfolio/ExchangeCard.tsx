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
import { FirstTimeAutoTradeModal } from './FirstTimeAutoTradeModal';
import { KillSwitchBanner } from './KillSwitchBanner';
import { RecentTradesList } from './RecentTradesList';
import { SettingsPanel } from './SettingsPanel';

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
  const [showAutoTradeConfirm, setShowAutoTradeConfirm] = useState(false);
  const [showSettings, setShowSettings] = useState(false);

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

  const firstTimeKey = `execution.auto-trade-confirmed.${account.id}`;

  const handleAutoTrade = () => {
    if (account.autoTradeEnabled) {
      // Disabling never needs confirmation.
      onPatch(account.id, { autoTradeEnabled: false });
      return;
    }
    const alreadyConfirmed = window.localStorage.getItem(firstTimeKey) === 'true';
    if (alreadyConfirmed) {
      onPatch(account.id, { autoTradeEnabled: true });
      return;
    }
    setShowAutoTradeConfirm(true);
  };

  const confirmAutoTradeEnable = () => {
    window.localStorage.setItem(firstTimeKey, 'true');
    setShowAutoTradeConfirm(false);
    onPatch(account.id, { autoTradeEnabled: true });
  };

  const handleKillSwitch = () => {
    onPatch(account.id, { killSwitch: !account.killSwitch });
  };

  const handleSettings = () => setShowSettings(true);

  const bodyClass = [
    account.killSwitch ? 'opacity-[0.85] [filter:grayscale(0.3)]' : '',
    stream.connectionState !== 'connected' && !account.killSwitch ? 'opacity-70' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const cardBorder = account.killSwitch ? 'border-[#ef4444]' : 'border-[#1c1f27]';

  return (
    <div className={`relative rounded-lg border ${cardBorder} bg-[#141820] p-4`}>
      {account.killSwitch && (
        <KillSwitchBanner onDisarm={() => onPatch(account.id, { killSwitch: false })} />
      )}
      <div className={bodyClass}>
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
        {account.killSwitch && (
          <div className="mt-3 rounded bg-[#0f1116] p-2 text-center text-[10px] text-gray-500">
            positions still tracked — only NEW signals blocked
          </div>
        )}
        <div className="mt-4 mb-2 text-[11px] uppercase tracking-wide text-gray-500">Recent closed (last 24h)</div>
        <RecentTradesList
          trades={stream.trades}
          onShowAll={() => console.log('trade ledger — wired in Task 13')}
        />
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
      {showAutoTradeConfirm && (
        <FirstTimeAutoTradeModal
          account={account}
          onConfirm={confirmAutoTradeEnable}
          onCancel={() => setShowAutoTradeConfirm(false)}
        />
      )}
      {showSettings && (
        <SettingsPanel
          account={account}
          onClose={() => setShowSettings(false)}
          onSave={(diff) => onPatch(account.id, diff)}
        />
      )}
    </div>
  );
}
