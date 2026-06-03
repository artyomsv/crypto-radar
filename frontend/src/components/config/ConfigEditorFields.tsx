import { cn } from '@/lib/utils';

interface FieldProps {
  label: string;
  tooltip: string;
  value: number;
  min?: number;
  max?: number;
  step?: number;
  onChange: (v: number) => void;
}

export function NumberField({ label, tooltip, value, min, max, step = 0.01, onChange }: FieldProps) {
  const hasSlider = min !== undefined && max !== undefined;

  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between">
        <label className="text-[11px] text-text-secondary" title={tooltip}>
          {label}
        </label>
        <input
          type="number"
          value={value}
          min={min}
          max={max}
          step={step}
          onChange={(e) => onChange(parseFloat(e.target.value) || 0)}
          className="w-20 px-2 py-0.5 rounded bg-surface border border-surface-border text-xs font-mono text-text-primary text-right focus:outline-none focus:border-accent/60"
        />
      </div>
      {hasSlider && (
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(parseFloat(e.target.value))}
          className="w-full h-1 accent-[var(--color-accent)] cursor-pointer"
        />
      )}
    </div>
  );
}

interface SectionProps {
  title: string;
  children: React.ReactNode;
}

export function Section({ title, children }: SectionProps) {
  return (
    <div className="space-y-3">
      <h3 className="text-xs font-semibold text-text-secondary uppercase tracking-wider border-b border-surface-border pb-1">
        {title}
      </h3>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-3">
        {children}
      </div>
    </div>
  );
}

interface TabButtonProps {
  label: string;
  isActive: boolean;
  onClick: () => void;
}

export function TabButton({ label, isActive, onClick }: TabButtonProps) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'px-3 py-1 rounded text-xs font-medium transition-colors',
        isActive
          ? 'bg-accent/20 text-accent border border-accent/40'
          : 'text-text-secondary hover:text-accent border border-transparent hover:border-accent/20'
      )}
    >
      {label}
    </button>
  );
}

interface ValidationPanelProps {
  weightSum: number;
  isValid: boolean;
}

export function ValidationPanel({ weightSum, isValid }: ValidationPanelProps) {
  return (
    <div className={cn(
      'flex items-center gap-2 px-3 py-2 rounded border text-xs',
      isValid
        ? 'bg-gain/5 border-gain/30 text-gain'
        : 'bg-loss/5 border-loss/30 text-loss'
    )}>
      <span className="font-bold">{isValid ? '✓' : '✗'}</span>
      {isValid
        ? `Weights sum to ${weightSum.toFixed(3)} ✓`
        : `Weights sum to ${weightSum.toFixed(3)} (must be 1.0)`}
    </div>
  );
}
