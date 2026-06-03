import { cn } from '@/lib/utils';
import type { StrategyKind } from '@/lib/optionsDerivations';

export function StrategyBadge({ kind }: { kind: StrategyKind }) {
    return (
        <span
            className={cn(
                'inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-bold tracking-wide uppercase border',
                kind === 'STRADDLE'
                    ? 'bg-accent/10 text-accent border-accent/50'
                    : 'bg-surface-secondary text-text-secondary border-surface-border',
            )}
        >
            {kind}
        </span>
    );
}
