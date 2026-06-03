import { useState, useEffect } from 'react';
import type { SignalConfigVersion } from '@/types';
import { cn } from '@/lib/utils';
import { api } from '@/lib/api';
import { Loader2, PlayCircle } from 'lucide-react';

type PeriodPreset = '7d' | '30d' | '90d' | 'custom';

const PRESET_DAYS: Record<Exclude<PeriodPreset, 'custom'>, number> = { '7d': 7, '30d': 30, '90d': 90 };
const MAX_CUSTOM_DAYS = 365;

function periodDatesForPreset(days: number): { start: string; end: string } {
  const end = new Date();
  const start = new Date(end.getTime() - days * 86_400_000);
  return {
    start: start.toISOString().slice(0, 10),
    end: end.toISOString().slice(0, 10),
  };
}

interface BacktestFormProps {
  onRunStarted: (versionId: number, periodStart: string, periodEnd: string, alignmentFloor: number | null) => void;
  isRunning: boolean;
  selectedVersionId: number | null;
  onVersionChange: (id: number) => void;
}

export function BacktestForm({ onRunStarted, isRunning, selectedVersionId, onVersionChange }: BacktestFormProps) {
  const [versions, setVersions] = useState<SignalConfigVersion[]>([]);
  const [loadingVersions, setLoadingVersions] = useState(true);
  const [preset, setPreset] = useState<PeriodPreset>('30d');
  const [customStart, setCustomStart] = useState('');
  const [customEnd, setCustomEnd] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [alignmentFloorEnabled, setAlignmentFloorEnabled] = useState(false);
  const [alignmentFloor, setAlignmentFloor] = useState<number>(70);

  useEffect(() => {
    api.listSignalConfigVersions(50, 0).then(data => {
      if (!data) return;
      setVersions(data);
      const active = data.find(v => v.isActive);
      if (active && selectedVersionId === null) {
        onVersionChange(active.id);
      }
      setLoadingVersions(false);
    });
  }, [onVersionChange, selectedVersionId]);

  function getPeriodDates(): { start: string; end: string } | null {
    if (preset !== 'custom') {
      return periodDatesForPreset(PRESET_DAYS[preset]);
    }
    return customStart && customEnd ? { start: customStart, end: customEnd } : null;
  }

  function handleRun() {
    setValidationError(null);
    if (!selectedVersionId) {
      setValidationError('Select a config version.');
      return;
    }
    const dates = getPeriodDates();
    if (!dates) {
      setValidationError('Enter a valid date range.');
      return;
    }
    if (preset === 'custom') {
      const start = new Date(dates.start).getTime();
      const end = new Date(dates.end).getTime();
      if (end <= start) {
        setValidationError('End date must be after start date.');
        return;
      }
      const days = (end - start) / 86_400_000;
      if (days > MAX_CUSTOM_DAYS) {
        setValidationError(`Period exceeds ${MAX_CUSTOM_DAYS}-day limit.`);
        return;
      }
    }
    onRunStarted(
        selectedVersionId,
        `${dates.start}T00:00:00Z`,
        `${dates.end}T00:00:00Z`,
        alignmentFloorEnabled ? alignmentFloor : null,
    );
  }

  return (
    <div className="glass-card p-4">
      <div className="flex flex-wrap items-end gap-4">
        {/* Config version selector */}
        <div className="flex flex-col gap-1 min-w-[200px]">
          <label className="text-[10px] text-text-secondary uppercase tracking-wide">Config Version</label>
          {loadingVersions ? (
            <div className="h-8 w-full rounded bg-white/5 animate-pulse" />
          ) : (
            <select
              value={selectedVersionId ?? ''}
              onChange={e => onVersionChange(Number(e.target.value))}
              className="bg-surface-secondary border border-surface-border text-text-primary text-sm rounded px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="" disabled>Select version…</option>
              {versions.map(v => (
                <option key={v.id} value={v.id}>
                  v{v.version}{v.isActive ? ' (active)' : ''}
                  {v.description ? ` — ${v.description.slice(0, 40)}` : ''}
                </option>
              ))}
            </select>
          )}
        </div>

        {/* Period presets */}
        <div className="flex flex-col gap-1">
          <label className="text-[10px] text-text-secondary uppercase tracking-wide">Period</label>
          <div className="flex items-center gap-1">
            {(['7d', '30d', '90d', 'custom'] as PeriodPreset[]).map(p => (
              <button
                key={p}
                onClick={() => setPreset(p)}
                className={cn(
                  'px-2.5 py-1.5 rounded text-xs font-medium transition-colors',
                  preset === p ? 'bg-accent text-white' : 'bg-surface-secondary text-text-secondary hover:text-accent border border-surface-border'
                )}
              >
                {p === 'custom' ? 'Custom' : p}
              </button>
            ))}
          </div>
        </div>

        {/* Custom date range */}
        {preset === 'custom' && (
          <div className="flex items-end gap-2">
            <div className="flex flex-col gap-1">
              <label className="text-[10px] text-text-secondary uppercase tracking-wide">From</label>
              <input
                type="date"
                value={customStart}
                onChange={e => setCustomStart(e.target.value)}
                className="bg-surface-secondary border border-surface-border text-text-primary text-sm rounded px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-[10px] text-text-secondary uppercase tracking-wide">To</label>
              <input
                type="date"
                value={customEnd}
                onChange={e => setCustomEnd(e.target.value)}
                className="bg-surface-secondary border border-surface-border text-text-primary text-sm rounded px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
          </div>
        )}

        {/* Alignment floor simulation (Vector D execution gate) */}
        <div className="flex flex-col gap-1">
          <label className="text-[10px] text-text-secondary uppercase tracking-wide">Alignment Floor</label>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={alignmentFloorEnabled}
              onChange={e => setAlignmentFloorEnabled(e.target.checked)}
              className="h-3.5 w-3.5 accent-[var(--color-accent)]"
              title="Simulate trade-execution-service's Vector D gate"
            />
            <input
              type="number"
              value={alignmentFloor}
              min={0}
              max={100}
              step={1}
              disabled={!alignmentFloorEnabled}
              onChange={e => setAlignmentFloor(Number(e.target.value) || 0)}
              className={cn(
                'w-16 bg-surface-secondary border border-surface-border text-text-primary text-sm rounded px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-accent',
                !alignmentFloorEnabled && 'opacity-40'
              )}
            />
          </div>
        </div>

        {/* Run button */}
        <button
          onClick={handleRun}
          disabled={isRunning || !selectedVersionId}
          className={cn(
            'flex items-center gap-1.5 px-4 py-1.5 rounded text-sm font-medium transition-colors',
            isRunning || !selectedVersionId
              ? 'bg-surface-secondary text-text-secondary cursor-not-allowed'
              : 'bg-accent text-white hover:bg-accent/80'
          )}
        >
          {isRunning ? (
            <><Loader2 className="h-3.5 w-3.5 animate-spin" />Running…</>
          ) : (
            <><PlayCircle className="h-3.5 w-3.5" />Run Backtest</>
          )}
        </button>
      </div>

      {validationError && (
        <p className="mt-2 text-xs text-loss">{validationError}</p>
      )}
    </div>
  );
}
