import { useEffect, useState } from 'react';
import { Loader2, Save, Shield } from 'lucide-react';
import { api } from '@/lib/api';
import type { ExecutionSettings } from '@/types';
import { NumberField, Section } from './ConfigEditorFields';
import { cn } from '@/lib/utils';

/**
 * Renders the runtime-tunable execution gates that live on
 * trade-execution-service (separate process from the SignalConfig knobs
 * above). PUT replaces the singleton row; gates hot-reload within 30s.
 */
export function ExecutionGatesPanel() {
  const [original, setOriginal] = useState<ExecutionSettings | null>(null);
  const [draft, setDraft] = useState<ExecutionSettings | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);

  useEffect(() => {
    api.getExecutionSettings().then((s) => {
      if (s) {
        setOriginal(s);
        setDraft(s);
      }
      setIsLoading(false);
    });
  }, []);

  const isDirty = original !== null && draft !== null
      && JSON.stringify(original) !== JSON.stringify(draft);

  const handleSave = async () => {
    if (!draft) return;
    setIsSaving(true);
    setError(null);
    try {
      const next = await api.updateExecutionSettings(draft);
      setOriginal(next);
      setDraft(next);
      setSavedAt(new Date());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setIsSaving(false);
    }
  };

  const update = <K extends keyof ExecutionSettings>(key: K, value: ExecutionSettings[K]) => {
    setDraft((d) => (d ? { ...d, [key]: value } : d));
  };

  if (isLoading) {
    return (
      <div className="glass-card p-5 flex items-center justify-center h-32">
        <Loader2 className="h-5 w-5 text-accent animate-spin" />
      </div>
    );
  }

  if (!draft) {
    return (
      <div className="glass-card p-5 text-sm text-text-secondary">
        Execution gates unavailable — trade-execution-service unreachable.
      </div>
    );
  }

  return (
    <div className="glass-card p-5 space-y-4">
      <div className="flex items-center justify-between border-b border-surface-border pb-2">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4 text-accent" />
          <h2 className="text-sm font-semibold text-text-primary">Execution Gates</h2>
          <span className="text-[10px] text-text-secondary">
            (trade-execution-service · 30s hot-reload)
          </span>
        </div>
        <div className="flex items-center gap-3">
          {savedAt && !isDirty && (
            <span className="text-[10px] text-gain">Saved {savedAt.toLocaleTimeString()}</span>
          )}
          <button
            onClick={handleSave}
            disabled={!isDirty || isSaving}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1 rounded text-xs font-medium border transition-colors',
              isDirty && !isSaving
                ? 'bg-accent/20 text-accent border-accent/40 hover:bg-accent/30'
                : 'bg-surface/40 text-text-secondary border-surface-border cursor-not-allowed'
            )}
          >
            {isSaving ? <Loader2 className="h-3 w-3 animate-spin" /> : <Save className="h-3 w-3" />}
            Apply
          </button>
        </div>
      </div>

      {error && (
        <div className="px-3 py-2 rounded border bg-loss/10 border-loss/40 text-loss text-xs">
          {error}
        </div>
      )}

      <Section title="Alignment Floor (Vector D)">
        <NumberField
          label="Alignment floor"
          tooltip="Minimum alignment to dispatch overview signals. Phase-2 data: 40-55 bucket lost 8.87R / 19 trades. Plan recommends lowering to 40 or 0 — high-alignment bucket has 3% win rate vs <40 = 18.8%."
          value={draft.alignmentFloor}
          min={0}
          max={100}
          step={1}
          onChange={(v) => update('alignmentFloor', v)}
        />
      </Section>

      <Section title="Symbol Performance Gate (Vector A)">
        <NumberField
          label="Threshold R"
          tooltip="Symbol suppressed when last N closed outcomes sum ≤ this. Plan recommends raising to -1.5 (tighter) — 5 symbols drain 90% of P&L."
          value={draft.symbolGateThresholdR}
          min={-20}
          max={0}
          step={0.5}
          onChange={(v) => update('symbolGateThresholdR', v)}
        />
        <NumberField
          label="Lookback (outcomes)"
          tooltip="Number of recent closed outcomes summed per symbol."
          value={draft.symbolGateLookback}
          min={1}
          max={50}
          step={1}
          onChange={(v) => update('symbolGateLookback', v)}
        />
        <NumberField
          label="Cache TTL (sec)"
          tooltip="How long the per-symbol decision is cached before re-querying signal_outcomes."
          value={draft.symbolGateCacheTtlSec}
          min={5}
          max={600}
          step={5}
          onChange={(v) => update('symbolGateCacheTtlSec', v)}
        />
        <div className="flex items-center gap-3 pt-1">
          <label className="text-[11px] text-text-secondary">Enabled</label>
          <input
            type="checkbox"
            checked={draft.symbolGateEnabled}
            onChange={(e) => update('symbolGateEnabled', e.target.checked)}
            className="h-3.5 w-3.5 accent-[var(--color-accent)]"
          />
        </div>
      </Section>

      <Section title="Detector Confluence (Vector B)">
        <NumberField
          label="Window (minutes)"
          tooltip="Max minutes between dimension-scoring and trend-continuation outcomes for confluence to hold."
          value={draft.confluenceWindowMinutes}
          min={1}
          max={240}
          step={1}
          onChange={(v) => update('confluenceWindowMinutes', v)}
        />
        <div className="flex items-center gap-3 pt-1">
          <label className="text-[11px] text-text-secondary">Trend-continuation requires confluence</label>
          <input
            type="checkbox"
            checked={draft.confluenceTrendRequired}
            onChange={(e) => update('confluenceTrendRequired', e.target.checked)}
            className="h-3.5 w-3.5 accent-[var(--color-accent)]"
          />
        </div>
      </Section>

      <Section title="Daily PnL Halt Cache">
        <NumberField
          label="Equity cache TTL (sec)"
          tooltip="How long Bybit wallet equity is cached before re-fetching for the daily-loss guard."
          value={draft.dailyPnlEquityCacheTtlSec}
          min={5}
          max={600}
          step={5}
          onChange={(v) => update('dailyPnlEquityCacheTtlSec', v)}
        />
      </Section>
    </div>
  );
}
