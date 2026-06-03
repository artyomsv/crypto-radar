import type { EnrichedOptionOpportunity } from '@/types';
import { cn } from '@/lib/utils';
import { LabelHint } from './LabelHint';

interface CardGreeksPanelProps {
    opportunity: EnrichedOptionOpportunity;
}

function fmt(n: number | null | undefined, digits = 2, suffix = ''): string {
    if (n === null || n === undefined) return '—';
    return n.toFixed(digits) + suffix;
}

function fmtCompact(n: number | null | undefined): string {
    if (n === null || n === undefined) return '—';
    const abs = Math.abs(n);
    if (abs >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
    if (abs >= 1_000) return (n / 1_000).toFixed(2) + 'K';
    return n.toFixed(2);
}

export function CardGreeksPanel({ opportunity }: CardGreeksPanelProps) {
    const noLegs = !opportunity.callLeg && !opportunity.putLeg;
    if (noLegs) {
        return (
            <div className="border-t border-surface-border/40 pt-2 text-[10px] text-text-secondary italic">
                Leg snapshots not yet ingested — Greeks will populate within the next poll.
            </div>
        );
    }

    const thetaCost = opportunity.netTheta != null ? Math.abs(opportunity.netTheta) : null;
    const thetaTone = opportunity.netTheta != null && opportunity.netTheta < 0
        ? 'text-loss' : 'text-text-secondary';

    return (
        <div className="border-t border-surface-border/40 pt-2 grid grid-cols-3 gap-y-1 gap-x-3 text-[10px]">
            <div>
                <LabelHint label="Net Δ" tip="netDelta" className="uppercase tracking-wide" />
                <div className="font-mono text-text-primary">{fmt(opportunity.netDelta, 3)}</div>
            </div>
            <div>
                <LabelHint label="Net Γ" tip="netGamma" className="uppercase tracking-wide" />
                <div className="font-mono text-text-primary">{fmt(opportunity.netGamma, 5)}</div>
            </div>
            <div>
                <LabelHint label="Theta/day" tip="thetaPerDay" className="uppercase tracking-wide" />
                <div className={cn('font-mono', thetaTone)}>
                    {thetaCost == null ? '—' : `−$${thetaCost.toFixed(2)}`}
                </div>
            </div>
            <div>
                <LabelHint label="Net Vega" tip="netVega" className="uppercase tracking-wide" />
                <div className="font-mono text-text-primary">{fmt(opportunity.netVega, 2)}</div>
            </div>
            <div>
                <LabelHint label="OI total" tip="totalOi" className="uppercase tracking-wide" />
                <div className="font-mono text-text-primary">{fmtCompact(opportunity.totalOpenInterest)}</div>
            </div>
            <div>
                <LabelHint label="24h Vol" tip="totalVol" className="uppercase tracking-wide" />
                <div className="font-mono text-text-primary">{fmtCompact(opportunity.totalVolume24h)}</div>
            </div>
        </div>
    );
}
