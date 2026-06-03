import { ArrowUpDown } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { SortKey } from '@/lib/optionsDerivations';

const LABELS: Record<SortKey, string> = {
    confidence: 'Confidence ↓',
    dte: 'DTE ↑',
    ivRvSpread: 'IV/RV gap ↓',
    premiumPct: 'Premium % ↑',
    detected: 'Newest first',
};

const SORT_OPTIONS: SortKey[] = ['confidence', 'dte', 'ivRvSpread', 'premiumPct', 'detected'];

interface SortControlsProps {
    value: SortKey;
    onChange: (key: SortKey) => void;
}

export function SortControls({ value, onChange }: SortControlsProps) {
    return (
        <div className="flex items-center gap-1.5">
            <ArrowUpDown className="h-3 w-3 text-text-secondary" />
            <span className="text-[10px] text-text-secondary uppercase tracking-wide">Sort</span>
            <select
                value={value}
                onChange={(e) => onChange(e.target.value as SortKey)}
                className={cn(
                    'bg-surface-secondary border border-surface-border text-text-primary',
                    'text-xs rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-accent',
                )}
            >
                {SORT_OPTIONS.map((k) => (
                    <option key={k} value={k}>
                        {LABELS[k]}
                    </option>
                ))}
            </select>
        </div>
    );
}
