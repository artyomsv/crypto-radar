import { cn } from '@/lib/utils';
import type { Verdict } from '@/lib/optionsDerivations';
import { VERDICT_LABEL } from '@/lib/optionsDerivations';

const TONE: Record<Verdict, string> = {
    STRONG_BUY: 'bg-gain/20 text-gain border-gain font-bold',
    BUY: 'bg-gain/10 text-gain border-gain/50',
    WAIT: 'bg-surface-secondary text-text-secondary border-surface-border',
    SKIP: 'bg-loss/10 text-loss border-loss/50',
};

export function VerdictBadge({ verdict }: { verdict: Verdict }) {
    return (
        <span
            className={cn(
                'inline-flex items-center px-2 py-0.5 rounded text-[10px] font-bold tracking-wide uppercase border',
                TONE[verdict],
            )}
        >
            {VERDICT_LABEL[verdict]}
        </span>
    );
}
