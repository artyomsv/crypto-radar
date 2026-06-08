import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import type { ExchangeAccount, UpdateAccountRequest } from '@/types';

interface Props {
  account: ExchangeAccount;
  onClose: () => void;
  onSave: (req: UpdateAccountRequest) => Promise<{ success: boolean; error?: string }>;
}

interface FormState {
  riskPercent: string;
  defaultLeverage: string;
  maxConcurrentPositions: string;
  maxDailyLossPercent: string;
  signalAgeSeconds: string;
  positionMaxAgeHours: string;
  flipPersistenceTicks: string;
}

function fromAccount(a: ExchangeAccount): FormState {
  return {
    riskPercent: String(a.riskPercent),
    defaultLeverage: String(a.defaultLeverage),
    maxConcurrentPositions: String(a.maxConcurrentPositions),
    maxDailyLossPercent: String(a.maxDailyLossPercent),
    signalAgeSeconds: String(a.signalAgeSeconds),
    positionMaxAgeHours: String(a.positionMaxAgeHours),
    flipPersistenceTicks: String(a.flipPersistenceTicks),
  };
}

export function SettingsPanel({ account, onClose, onSave }: Props) {
  const [form, setForm] = useState<FormState>(fromAccount(account));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', esc);
    return () => document.removeEventListener('keydown', esc);
  }, [onClose]);

  const setField = (field: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    const diff: UpdateAccountRequest = {};

    // Use Number() instead of parseFloat/parseInt — the latter silently
    // truncate trailing garbage ("3abc" → 3) and could PATCH unexpected
    // values. Number("3abc") → NaN, which the Finite check rejects.
    const parseDecimal = (k: keyof FormState, orig: number): number | undefined => {
      const raw = form[k].trim();
      if (raw === '') return undefined;
      const n = Number(raw);
      if (!Number.isFinite(n) || n === orig) return undefined;
      return n;
    };
    const parseInteger = (k: keyof FormState, orig: number): number | undefined => {
      const raw = form[k].trim();
      if (raw === '') return undefined;
      const n = Number(raw);
      if (!Number.isFinite(n) || !Number.isInteger(n) || n === orig) return undefined;
      return n;
    };

    const riskPercent = parseDecimal('riskPercent', account.riskPercent);
    const defaultLeverage = parseInteger('defaultLeverage', account.defaultLeverage);
    const maxConcurrentPositions = parseInteger('maxConcurrentPositions', account.maxConcurrentPositions);
    const maxDailyLossPercent = parseDecimal('maxDailyLossPercent', account.maxDailyLossPercent);
    const signalAgeSeconds = parseInteger('signalAgeSeconds', account.signalAgeSeconds);
    const positionMaxAgeHours = parseInteger('positionMaxAgeHours', account.positionMaxAgeHours);
    const flipPersistenceTicks = parseInteger('flipPersistenceTicks', account.flipPersistenceTicks);

    if (riskPercent != null) diff.riskPercent = riskPercent;
    if (defaultLeverage != null) diff.defaultLeverage = defaultLeverage;
    if (maxConcurrentPositions != null) diff.maxConcurrentPositions = maxConcurrentPositions;
    if (maxDailyLossPercent != null) diff.maxDailyLossPercent = maxDailyLossPercent;
    if (signalAgeSeconds != null) diff.signalAgeSeconds = signalAgeSeconds;
    if (positionMaxAgeHours != null) diff.positionMaxAgeHours = positionMaxAgeHours;
    if (flipPersistenceTicks != null) diff.flipPersistenceTicks = flipPersistenceTicks;

    if (Object.keys(diff).length === 0) {
      setSaving(false);
      onClose();
      return;
    }

    const result = await onSave(diff);
    setSaving(false);
    if (!result.success) {
      setError(result.error ?? 'save failed');
      return;
    }
    onClose();
  };

  return (
    <>
      <div className="absolute inset-0 z-40 bg-black/40" onClick={onClose} />
      <div className="absolute right-0 top-0 z-50 h-full w-[260px] overflow-y-auto border-l border-[#2a3040] bg-[#0a0d14] p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="text-[11px] font-semibold text-white">Settings</div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-white"
            aria-label="Close"
          >
            <X size={14} />
          </button>
        </div>
        <Field label="Risk / trade (%)" value={form.riskPercent} onChange={setField('riskPercent')} />
        <Field label="Default leverage (×)" value={form.defaultLeverage} onChange={setField('defaultLeverage')} />
        <Field label="Max concurrent" value={form.maxConcurrentPositions} onChange={setField('maxConcurrentPositions')} />
        <Field label="Daily loss halt (%)" value={form.maxDailyLossPercent} onChange={setField('maxDailyLossPercent')} />
        <Field label="Signal max age (s)" value={form.signalAgeSeconds} onChange={setField('signalAgeSeconds')} />
        <Field label="Position max age (h)" value={form.positionMaxAgeHours} onChange={setField('positionMaxAgeHours')} />
        <Field label="Flip persistence (ticks)" value={form.flipPersistenceTicks} onChange={setField('flipPersistenceTicks')} />
        {error && (
          <div className="mb-2 rounded border border-[#ef4444] bg-[#2d0e0e] p-2 text-[10px] text-[#ef4444]">
            {error}
          </div>
        )}
        <div className="mt-3 flex gap-2">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 rounded bg-[#222] px-2 py-2 text-[11px] text-gray-300 hover:bg-[#2a2f38]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving}
            className="flex-1 rounded bg-[#1a73e8] px-2 py-2 text-[11px] font-semibold text-white hover:bg-[#1666d0] disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </>
  );
}

interface FieldProps {
  label: string;
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}

function Field({ label, value, onChange }: FieldProps) {
  return (
    <div className="mb-2.5">
      <div className="mb-1 text-[9px] uppercase text-gray-400">{label}</div>
      <input
        type="text"
        value={value}
        onChange={onChange}
        className="w-full rounded bg-[#141820] px-2 py-1.5 text-[11px] text-white"
      />
    </div>
  );
}
