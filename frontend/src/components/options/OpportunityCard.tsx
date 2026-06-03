import { Calendar, Activity, Zap } from 'lucide-react';
import type { EnrichedOptionOpportunity, HitRateBucket } from '@/types';
import { cn } from '@/lib/utils';
import {
    breakEvenPct,
    classifyStrategy,
    entryUnderlyingPx,
    formatPrice,
    impliedDailyMovePct,
    lookupHitRate,
    OPTIONS_CONFIDENCE_THRESHOLD,
    premiumPctOfSpot,
    timeToExpiry,
    underlyingPx,
    verdict,
    whyText,
} from '@/lib/optionsDerivations';
import { AlertTriangle } from 'lucide-react';
import { OPTION_TOOLTIPS } from '@/lib/optionsTooltips';
import { StrategyBadge } from './StrategyBadge';
import { VerdictBadge } from './VerdictBadge';
import { CardGreeksPanel } from './CardGreeksPanel';
import { CardHitRate } from './CardHitRate';
import { LabelHint } from './LabelHint';

interface OpportunityCardProps {
    opportunity: EnrichedOptionOpportunity;
    hitRateBuckets: HitRateBucket[];
}

function fmt(n: number | null | undefined, digits = 2): string {
    if (n === null || n === undefined) return '—';
    return n.toFixed(digits);
}

function fmtPct(n: number | null | undefined, digits = 1): string {
    if (n === null || n === undefined) return '—';
    return n.toFixed(digits) + '%';
}

function formatDetectedAgo(detectedAt: string): string {
    const ms = Date.now() - new Date(detectedAt).getTime();
    if (ms < 0) return 'just now';
    const minutes = Math.floor(ms / 60_000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
}

export function OpportunityCard({ opportunity, hitRateBuckets }: OpportunityCardProps) {
    const strategy = classifyStrategy(opportunity);
    const v = verdict(opportunity);
    const dte = timeToExpiry(opportunity);
    const spot = underlyingPx(opportunity);
    const entrySpot = entryUnderlyingPx(opportunity);
    const premPct = premiumPctOfSpot(opportunity);
    const be = breakEvenPct(opportunity);
    const implied = impliedDailyMovePct(opportunity);

    // Show LIVE values as primary on the card. Entry values are shown as
    // small secondary "was X" comparisons when they have drifted enough
    // to matter. Drift > 10% on any field is worth surfacing.
    const liveConf = opportunity.liveConfidence ?? opportunity.confidence;
    const liveIv = opportunity.liveImpliedVolAtm ?? opportunity.impliedVolAtm;
    const liveGap = opportunity.liveIvRvSpread ?? opportunity.ivRvSpread;
    const livePrem = opportunity.livePremium ?? opportunity.stranglePremium;
    const drift = (live: number | null, entry: number | null): number | null => {
        if (live == null || entry == null || entry === 0) return null;
        return ((live - entry) / Math.abs(entry)) * 100;
    };
    const confDrift = drift(liveConf, opportunity.confidence);
    const ivDrift = drift(
        liveIv != null ? liveIv * 100 : null,
        opportunity.impliedVolAtm != null ? opportunity.impliedVolAtm * 100 : null,
    );
    const premDrift = drift(livePrem, opportunity.stranglePremium);
    const spotDrift = drift(spot, entrySpot);

    const ivRvGapTone = (liveGap ?? 0) > 0 ? 'text-gain' : 'text-loss';
    const confTone =
        liveConf >= 85 ? 'text-gain' :
        liveConf >= OPTIONS_CONFIDENCE_THRESHOLD ? 'text-accent' :
        liveConf >= 50 ? 'text-text-secondary' : 'text-loss';
    const hit = lookupHitRate(opportunity, hitRateBuckets);

    // Stale cards still render — they may be filtered out by the resource, but
    // when the `includeStale=true` query is used we want them visibly flagged.
    const containerClass = opportunity.isStale
        ? 'glass-card p-3 space-y-2 border-loss/40 bg-loss/5'
        : 'glass-card p-3 space-y-2';

    return (
        <div className={containerClass}>
            {/* STALE banner — only shown when isStale (resource hides by default) */}
            {opportunity.isStale && (
                <div
                    className="flex items-center gap-1.5 px-2 py-1 -mx-1 -mt-1 mb-1 rounded bg-loss/15 border border-loss/40 text-loss text-[10px] font-bold cursor-help"
                    title={OPTION_TOOLTIPS.stale}
                >
                    <AlertTriangle className="h-3 w-3" />
                    <span className="uppercase tracking-wide">Stale</span>
                    {opportunity.staleReason && (
                        <span className="font-normal normal-case text-text-secondary">
                            — {opportunity.staleReason}
                        </span>
                    )}
                </div>
            )}
            {/* Header: strategy + DTE + verdict + confidence */}
            <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-1.5 flex-wrap">
                    <span title={OPTION_TOOLTIPS.strategy} className="cursor-help">
                        <StrategyBadge kind={strategy} />
                    </span>
                    <span
                        className="inline-flex items-center gap-1 text-[10px] text-text-secondary uppercase tracking-wide cursor-help"
                        title={OPTION_TOOLTIPS.dte}
                    >
                        <Calendar className="h-3 w-3" />
                        {dte.label}
                    </span>
                    <span title={OPTION_TOOLTIPS.verdict} className="cursor-help">
                        <VerdictBadge verdict={v} />
                    </span>
                </div>
                <span
                    className={cn('text-sm font-bold font-mono cursor-help inline-flex items-baseline gap-1', confTone)}
                    title={OPTION_TOOLTIPS.liveConfidence}
                >
                    {fmt(liveConf, 1)}
                    {confDrift != null && Math.abs(confDrift) >= 5 && (
                        <span
                            className={cn(
                                'text-[10px] font-normal',
                                confDrift < 0 ? 'text-loss' : 'text-gain',
                            )}
                            title={OPTION_TOOLTIPS.entryVsLive}
                        >
                            (was {fmt(opportunity.confidence, 0)})
                        </span>
                    )}
                </span>
            </div>

            {/* Title row */}
            <div className="flex items-baseline justify-between">
                <div className="flex items-baseline gap-2">
                    <span className="text-base font-bold text-text-primary">{opportunity.underlying}</span>
                    <span className="text-xs text-text-secondary font-mono">{opportunity.expiry}</span>
                </div>
                <span
                    className="text-[10px] text-text-secondary cursor-help"
                    title={OPTION_TOOLTIPS.detected}
                >
                    {formatDetectedAgo(opportunity.detectedAt)}
                </span>
            </div>

            {/* Strike + premium row */}
            <div className="grid grid-cols-4 gap-2 text-xs">
                <div>
                    <LabelHint label="Call K" tip="callStrike" className="text-[10px] uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">{formatPrice(opportunity.strikeCall)}</div>
                </div>
                <div>
                    <LabelHint label="Put K" tip="putStrike" className="text-[10px] uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">{formatPrice(opportunity.strikePut)}</div>
                </div>
                <div>
                    <LabelHint label="Premium" tip="livePremium" className="text-[10px] uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">
                        {formatPrice(livePrem)}
                        {premDrift != null && Math.abs(premDrift) >= 10 && (
                            <span
                                className={cn(
                                    'ml-1 text-[9px] font-normal',
                                    premDrift > 0 ? 'text-loss' : 'text-gain',
                                )}
                                title={OPTION_TOOLTIPS.entryVsLive}
                            >
                                (was {formatPrice(opportunity.stranglePremium)})
                            </span>
                        )}
                    </div>
                </div>
                <div>
                    <LabelHint label="% of spot" tip="premiumPct" className="text-[10px] uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">{fmtPct(premPct, 2)}</div>
                </div>
            </div>

            {/* Breakeven row */}
            {(be != null || implied != null) && (
                <div className="border-t border-surface-border/40 pt-2 grid grid-cols-2 gap-2 text-[11px]">
                    <div>
                        <LabelHint label="Spot must move" tip="breakeven" className="text-[10px] uppercase tracking-wide" />
                        <div className="text-text-primary font-mono font-bold">±{fmt(be, 1)}%</div>
                    </div>
                    <div>
                        <LabelHint label="Implied daily" tip="impliedDaily" className="text-[10px] uppercase tracking-wide" />
                        <div className="text-text-primary font-mono">{fmtPct(implied, 2)}</div>
                    </div>
                </div>
            )}

            {/* Vol panel */}
            <div className="border-t border-surface-border/40 pt-2 grid grid-cols-4 gap-2 text-[10px]">
                <div>
                    <LabelHint label="IV ATM" tip="ivAtm" className="uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">
                        {fmtPct(liveIv != null ? liveIv * 100 : null, 0)}
                        {ivDrift != null && Math.abs(ivDrift) >= 10 && (
                            <span
                                className={cn(
                                    'ml-1 text-[9px] font-normal',
                                    ivDrift > 0 ? 'text-loss' : 'text-gain',
                                )}
                                title={OPTION_TOOLTIPS.entryVsLive}
                            >
                                ({ivDrift > 0 ? '+' : ''}{ivDrift.toFixed(0)}%)
                            </span>
                        )}
                    </div>
                </div>
                <div>
                    <LabelHint label="RV 7d" tip="rv7d" className="uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">{fmtPct(opportunity.realizedVol7d, 0)}</div>
                </div>
                <div>
                    <LabelHint label="RV 14d" tip="rv14d" className="uppercase tracking-wide" />
                    <div className="text-text-primary font-mono">
                        <Activity className="inline h-3 w-3 text-accent" /> {fmtPct(opportunity.realizedVol14d, 0)}
                    </div>
                </div>
                <div>
                    <LabelHint label="Gap" tip="liveIvRvGap" className="uppercase tracking-wide" />
                    <div className={cn('font-mono', ivRvGapTone)}>{fmtPct(liveGap, 0)}</div>
                </div>
            </div>

            {/* Signal panel */}
            {opportunity.signalOverlay != null && (
                <div className="border-t border-surface-border/40 pt-2 flex items-center gap-2 text-[10px]">
                    <Zap className="h-3 w-3 text-accent" />
                    <LabelHint label="Signal overlay" tip="signalOverlay" className="uppercase tracking-wide" />
                    <div className="flex-1 h-1.5 bg-surface-secondary rounded overflow-hidden">
                        <div
                            className="h-full bg-accent transition-all"
                            style={{ width: `${Math.min(100, opportunity.signalOverlay)}%` }}
                        />
                    </div>
                    <span className="text-text-primary font-mono font-bold">{fmt(opportunity.signalOverlay, 0)}</span>
                </div>
            )}

            {/* Greeks (renders own border-t) */}
            <CardGreeksPanel opportunity={opportunity} />

            {/* Hit rate (renders own border-t) */}
            <CardHitRate hit={hit} />

            {/* Why */}
            <div
                className="border-t border-surface-border/40 pt-2 text-[11px] text-text-secondary italic leading-snug cursor-help"
                title={OPTION_TOOLTIPS.why}
            >
                {whyText(opportunity)}
            </div>

            {/* Spot reference — live spot first, entry shown when drift is material */}
            {spot != null && (
                <div
                    className="text-[10px] text-text-secondary text-right font-mono cursor-help"
                    title={OPTION_TOOLTIPS.spotPrice}
                >
                    spot @ ${formatPrice(spot)}
                    {spotDrift != null && Math.abs(spotDrift) >= 1 && entrySpot != null && (
                        <span
                            className={cn(
                                'ml-1',
                                spotDrift > 0 ? 'text-gain' : 'text-loss',
                            )}
                            title={OPTION_TOOLTIPS.entryVsLive}
                        >
                            ({spotDrift > 0 ? '+' : ''}{spotDrift.toFixed(1)}% from ${formatPrice(entrySpot)})
                        </span>
                    )}
                </div>
            )}
        </div>
    );
}
