import { cn } from '@/lib/utils';

export type GroupMode = 'none' | 'strategy' | 'dte';

const OPTIONS: { value: GroupMode; label: string }[] = [
    { value: 'none', label: 'No grouping' },
    { value: 'strategy', label: 'By strategy' },
    { value: 'dte', label: 'By DTE' },
];

interface GroupingToggleProps {
    value: GroupMode;
    onChange: (mode: GroupMode) => void;
}

export function GroupingToggle({ value, onChange }: GroupingToggleProps) {
    return (
        <div className="flex items-center gap-1.5">
            <span className="text-[10px] text-text-secondary uppercase tracking-wide">Group</span>
            <div className="inline-flex bg-surface-secondary border border-surface-border rounded overflow-hidden">
                {OPTIONS.map((opt) => (
                    <button
                        key={opt.value}
                        onClick={() => onChange(opt.value)}
                        className={cn(
                            'px-2 py-1 text-[10px] font-medium transition-colors',
                            value === opt.value
                                ? 'bg-accent/20 text-accent'
                                : 'text-text-secondary hover:text-text-primary',
                        )}
                    >
                        {opt.label}
                    </button>
                ))}
            </div>
        </div>
    );
}
