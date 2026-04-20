import { useState } from 'react';
import { useExecutionAccounts } from '@/hooks/useExecutionAccounts';
import { AddExchangeButton } from './AddExchangeButton';
import { ExchangeSetupModal } from './ExchangeSetupModal';
import type { ExchangeAccount } from '@/types';

export function ExchangeAccountsSection() {
  const { accounts, loading, create } = useExecutionAccounts();
  const [showSetup, setShowSetup] = useState(false);

  if (loading) {
    return (
      <div className="rounded-lg bg-[#141820] p-6 text-center text-xs text-gray-500">
        Loading exchanges…
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {accounts.length === 0 && <AddExchangeButton onClick={() => setShowSetup(true)} />}
      {accounts.map((account) => (
        <ExchangeCardPlaceholder key={account.id} account={account} />
      ))}
      {accounts.length > 0 && (
        <button
          onClick={() => setShowSetup(true)}
          className="rounded-lg border border-dashed border-[#333] bg-[#141820] py-3 text-xs text-gray-500 hover:border-[#1a73e8] hover:text-gray-300"
        >
          + Add another exchange
        </button>
      )}
      {showSetup && (
        <ExchangeSetupModal
          mainnetEnabled={false}
          onClose={() => setShowSetup(false)}
          onSubmit={create}
        />
      )}
    </div>
  );
}

// Placeholder — replaced by real ExchangeCard in Task 4.
function ExchangeCardPlaceholder({ account }: { account: ExchangeAccount }) {
  return (
    <div className="rounded-lg border border-[#1c1f27] bg-[#141820] p-4">
      <div className="text-sm font-semibold text-white">
        {account.exchange} ({account.environment})
      </div>
      <div className="text-[10px] text-gray-500">
        id={account.id} · kill={String(account.killSwitch)} · auto={String(account.autoTradeEnabled)}
      </div>
      <div className="mt-2 text-[10px] text-gray-500">(ExchangeCard renders here in Task 4)</div>
    </div>
  );
}
