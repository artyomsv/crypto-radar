import type { HitRateBucket } from '@/types';
import { cn } from '@/lib/utils';
import { OPTION_TOOLTIPS } from '@/lib/optionsTooltips';
import { Info } from 'lucide-react';

interface CardHitRateProps {
    hit: HitRateBucket | null;
}

export function CardHitRate({ hit }: CardHitRateProps) {
    if (!hit || hit.winRate == null) return null;
    const pct = hit.winRate * 100;
    const tone = pct >= 60 ? 'text-gain' : pct >= 50 ? 'text-text-primary' : 'text-loss';
    return (
        <div
            className="border-t border-surface-border/40 pt-2 text-[10px] cursor-help"
            title={OPTION_TOOLTIPS.hitRate}
        >
            <span className="inline-flex items-center gap-0.5 text-text-secondary uppercase tracking-wide">
                Historical
                <Info className="h-2.5 w-2.5 opacity-40" aria-hidden="true" />
            </span>{' '}
            <span className={cn('font-mono font-bold', tone)}>{pct.toFixed(0)}%</span>
            <span className="text-text-secondary"> win at conf {hit.confidenceBucket} in {hit.underlying} </span>
            <span className="text-text-secondary font-mono">(N={hit.sampleSize}</span>
            {hit.avgPnlPct != null && (
                <span className="text-text-secondary">, avg {hit.avgPnlPct >= 0 ? '+' : ''}{hit.avgPnlPct.toFixed(1)}%</span>
            )}
            <span className="text-text-secondary">)</span>
        </div>
    );
}
