import { useState } from 'react';
import { useExecutionAccounts } from '@/hooks/useExecutionAccounts';
import { AddExchangeButton } from './AddExchangeButton';
import { ExchangeSetupModal } from './ExchangeSetupModal';
import { ExchangeCard } from './ExchangeCard';

export function ExchangeAccountsSection() {
  const { accounts, loading, error, create, patch } = useExecutionAccounts();
  const [showSetup, setShowSetup] = useState(false);

  if (loading) {
    return (
      <div className="rounded-lg bg-[#141820] p-6 text-center text-xs text-gray-500">
        Loading exchanges…
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-lg border border-[#ef4444] bg-[#2d0e0e] p-4 text-center text-xs text-[#ef4444]">
        Could not load exchange accounts: {error}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {accounts.length === 0 && <AddExchangeButton onClick={() => setShowSetup(true)} />}
      {accounts.map((account) => (
        <ExchangeCard key={account.id} account={account} onPatch={patch} />
      ))}
      {accounts.length > 0 && (
        <button
          type="button"
          onClick={() => setShowSetup(true)}
          className="rounded-lg border border-dashed border-[#333] bg-[#141820] py-3 text-xs text-gray-500 hover:border-[#1a73e8] hover:text-gray-300"
        >
          + Add another exchange
        </button>
      )}
      {showSetup && (
        // TODO: plumb from server — trade-execution-service reads execution.mainnet.enabled
        // but doesn't expose it via REST yet. Backend rejects MAINNET creation regardless
        // (PermissionValidator returns 400), so a false-by-default client gate is safe.
        <ExchangeSetupModal
          mainnetEnabled={false}
          onClose={() => setShowSetup(false)}
          onSubmit={create}
        />
      )}
    </div>
  );
}
