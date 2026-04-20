import { useState } from 'react';
import { X } from 'lucide-react';
import type { CreateAccountRequest } from '@/types';

interface Props {
  onClose: () => void;
  onSubmit: (req: CreateAccountRequest) => Promise<
    | { success: true }
    | { success: false; error: string; status: number }
  >;
  mainnetEnabled: boolean;
}

export function ExchangeSetupModal({ onClose, onSubmit, mainnetEnabled }: Props) {
  const [environment, setEnvironment] = useState<'DEMO' | 'MAINNET'>('DEMO');
  const [label, setLabel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [apiSecret, setApiSecret] = useState('');
  const [showSecret, setShowSecret] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    setError(null);
    if (!apiKey.trim() || !apiSecret.trim()) {
      setError('API key and secret are required.');
      return;
    }
    setSubmitting(true);
    const result = await onSubmit({
      exchange: 'BYBIT',
      environment,
      apiKey: apiKey.trim(),
      apiSecret: apiSecret.trim(),
      label: label.trim() || undefined,
    });
    setSubmitting(false);
    if (!result.success) {
      setError(result.error);
      return;
    }
    onClose();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={onClose}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          handleSubmit();
        }}
        className="w-[420px] rounded-lg border border-[#1c1f27] bg-[#141820] p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <div className="text-sm font-semibold text-white">Add Bybit account</div>
          <button type="button" onClick={onClose} className="text-gray-400 hover:text-white">
            <X size={16} />
          </button>
        </div>

        {!mainnetEnabled && (
          <div className="mb-3 rounded border border-[#f7a600] bg-[#2d1a0e] p-2 text-[10px] text-[#f7a600]">
            DEMO only until server-side mainnet flag is set
          </div>
        )}

        <div className="mb-3">
          <div className="mb-1 text-[10px] uppercase text-gray-400">Environment</div>
          <div className="flex gap-1.5">
            <button
              type="button"
              onClick={() => setEnvironment('DEMO')}
              className={`flex-1 rounded px-2 py-2 text-xs font-semibold ${
                environment === 'DEMO' ? 'bg-[#1a73e8] text-white' : 'bg-[#222] text-gray-400'
              }`}
            >
              DEMO
            </button>
            <button
              type="button"
              disabled={!mainnetEnabled}
              onClick={() => mainnetEnabled && setEnvironment('MAINNET')}
              className={`flex-1 rounded px-2 py-2 text-xs ${
                environment === 'MAINNET'
                  ? 'bg-[#1a73e8] text-white font-semibold'
                  : 'bg-[#222] text-gray-500'
              } ${!mainnetEnabled ? 'cursor-not-allowed' : ''}`}
            >
              MAINNET {!mainnetEnabled && '(locked)'}
            </button>
          </div>
        </div>

        <div className="mb-3">
          <label className="mb-1 block text-[10px] uppercase text-gray-400">Label (optional)</label>
          <input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="My demo account"
            className="w-full rounded border border-[#222] bg-[#0f1116] px-2 py-2 text-xs text-white placeholder-gray-600"
          />
        </div>

        <div className="mb-3">
          <label className="mb-1 block text-[10px] uppercase text-gray-400">API key</label>
          <input
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="XR7Dg8..."
            className="w-full rounded border border-[#222] bg-[#0f1116] px-2 py-2 text-xs text-white placeholder-gray-600"
          />
        </div>

        <div className="mb-3">
          <label className="mb-1 block text-[10px] uppercase text-gray-400">API secret</label>
          <div className="relative">
            <input
              type={showSecret ? 'text' : 'password'}
              value={apiSecret}
              onChange={(e) => setApiSecret(e.target.value)}
              placeholder="••••••••••••"
              className="w-full rounded border border-[#222] bg-[#0f1116] px-2 py-2 pr-12 text-xs text-white placeholder-gray-600"
            />
            <button
              type="button"
              onClick={() => setShowSecret((s) => !s)}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-gray-400 hover:text-white"
            >
              {showSecret ? 'hide' : 'show'}
            </button>
          </div>
        </div>

        <div className="mb-4 rounded border-l-2 border-[#1a73e8] bg-[#141820] p-2 text-[10px] leading-relaxed text-gray-400">
          On Bybit: <span className="text-gray-200 font-semibold">Derivatives → Order + Position</span>.
          NOT Withdraw. We reject withdraw-enabled keys.
        </div>

        {error && (
          <div className="mb-3 rounded border border-[#ef4444] bg-[#2d0e0e] p-2 text-[11px] text-[#ef4444]">
            {error}
          </div>
        )}

        <div className="flex gap-2">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 rounded bg-[#222] px-2 py-2 text-xs text-gray-300 hover:bg-[#2a2f38]"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="flex-1 rounded bg-[#4ade80] px-2 py-2 text-xs font-semibold text-black hover:bg-[#6ee498] disabled:opacity-50"
          >
            {submitting ? 'Validating…' : 'Validate + save'}
          </button>
        </div>
      </form>
    </div>
  );
}
