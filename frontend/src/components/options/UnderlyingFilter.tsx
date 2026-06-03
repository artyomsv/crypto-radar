import { cn } from '@/lib/utils';

const UNDERLYINGS = ['BTC', 'ETH', 'SOL', 'XAUT', 'XRP', 'MNT', 'DOGE'] as const;

interface UnderlyingFilterProps {
    selected: Set<string>;
    onToggle: (underlying: string) => void;
    onReset: () => void;
}

export function UnderlyingFilter({ selected, onToggle, onReset }: UnderlyingFilterProps) {
    const allSelected = selected.size === UNDERLYINGS.length;
    return (
        <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-[10px] text-text-secondary uppercase tracking-wide">Underlying</span>
            {UNDERLYINGS.map((u) => {
                const isOn = selected.has(u);
                return (
                    <button
                        key={u}
                        onClick={() => onToggle(u)}
                        className={cn(
                            'px-2 py-0.5 rounded text-[10px] font-bold uppercase border transition-colors',
                            isOn
                                ? 'bg-accent/10 text-accent border-accent/50'
                                : 'bg-surface-secondary text-text-secondary border-surface-border hover:text-text-primary',
                        )}
                    >
                        {u}
                    </button>
                );
            })}
            {!allSelected && (
                <button
                    onClick={onReset}
                    className="text-[10px] text-text-secondary hover:text-accent ml-1"
                >
                    reset
                </button>
            )}
        </div>
    );
}
