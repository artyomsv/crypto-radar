import type { EnrichedOptionOpportunity, HitRateBucket } from '@/types';

export type Verdict = 'STRONG_BUY' | 'BUY' | 'WAIT' | 'SKIP';
export type StrategyKind = 'STRADDLE' | 'STRANGLE';

const STRIKE_EPSILON_PCT = 0.001;

/**
 * Mirrors `options.opportunity.confidence-threshold` in the options-service
 * application.properties. Bump both when retuning. Drives the BUY verdict
 * floor and the empty-state copy on `/options`.
 */
export const OPTIONS_CONFIDENCE_THRESHOLD = 75;

/**
 * Magnitude-aware price formatter. Crypto prices span BTC (~$68 000) to DOGE
 * (~$0.10) and option strikes down to ~$0.098, so a fixed decimal count either
 * over-rounds low-priced tokens (0.098 → "0.10" or "0") or adds noise to large
 * ones. Decimals are chosen from the value's magnitude, then trailing zeros are
 * trimmed so 0.098 reads "0.098" and 68000 reads "68000".
 */
export function formatPrice(v: number | null | undefined): string {
    if (v == null) return '—';
    if (v === 0) return '0';
    const abs = Math.abs(v);
    let digits: number;
    if (abs >= 1000) digits = 0;
    else if (abs >= 1) digits = 2;
    else if (abs >= 0.1) digits = 4;
    else if (abs >= 0.001) digits = 5;
    else digits = 8;
    return v.toFixed(digits).replace(/\.?0+$/, '');
}

/**
 * Strategy classification — derived from the two leg strikes.
 *
 * A pure ATM straddle has identical call and put strike. Bybit's strike
 * grid is discrete, so we treat strikes within 0.1% of each other as
 * the same strike (effectively a straddle).
 */
export function classifyStrategy(opp: EnrichedOptionOpportunity): StrategyKind {
    const diff = Math.abs(opp.strikeCall - opp.strikePut);
    const ref = (opp.strikeCall + opp.strikePut) / 2 || 1;
    return diff / ref <= STRIKE_EPSILON_PCT ? 'STRADDLE' : 'STRANGLE';
}

/**
 * Verdict from LIVE values when available — live confidence + live signal
 * overlay reflect the current market, not the moment of detection.
 * Stale opportunities always read SKIP.
 */
export function verdict(opp: EnrichedOptionOpportunity): Verdict {
    if (opp.isStale) return 'SKIP';
    const conf = opp.liveConfidence ?? opp.confidence;
    const overlay = opp.liveSignalOverlay ?? opp.signalOverlay ?? 0;
    if (conf >= 85 && overlay >= 60) return 'STRONG_BUY';
    if (conf >= OPTIONS_CONFIDENCE_THRESHOLD) return 'BUY';
    if (conf >= 50) return 'WAIT';
    return 'SKIP';
}

/**
 * Days/hours/minutes between now and the expiry date. Bybit options expire
 * at 08:00 UTC on their expiry date; treat the displayed expiry as that
 * moment so a "2026-05-28" expiry registers as ~22h away on the 27th.
 */
export function timeToExpiry(opp: EnrichedOptionOpportunity): { ms: number; label: string } {
    const expiry = new Date(`${opp.expiry}T08:00:00Z`).getTime();
    const ms = expiry - Date.now();
    if (ms <= 0) return { ms, label: 'Expired' };
    const hours = Math.floor(ms / 3_600_000);
    const days = Math.floor(hours / 24);
    const remHours = hours - days * 24;
    if (days === 0) return { ms, label: `Today ${remHours}h` };
    if (days === 1) return { ms, label: `Tomorrow ${remHours}h` };
    return { ms, label: `${days}d ${remHours}h` };
}

export type DteBucket = 'today' | 'tomorrow' | 'two_three' | 'four_plus';

export function dteBucket(opp: EnrichedOptionOpportunity): DteBucket {
    const { ms } = timeToExpiry(opp);
    const hours = ms / 3_600_000;
    if (hours < 24) return 'today';
    if (hours < 48) return 'tomorrow';
    if (hours < 96) return 'two_three';
    return 'four_plus';
}

export const DTE_BUCKET_LABEL: Record<DteBucket, string> = {
    today: 'Today (<24h)',
    tomorrow: 'Tomorrow (24-48h)',
    two_three: '2-3 days',
    four_plus: '4+ days',
};

/**
 * Underlying spot price at detection time. Stored in the opportunity's
 * metadata JSONB by OpportunityScorer (key: underlyingPx). Falls back to
 * the call-leg's underlyingPx from the chain if metadata is missing.
 */
/**
 * Live spot when available (from the freshest leg snapshot), falling back
 * to the entry-time underlyingPx stored in metadata. Live preferred so
 * breakeven / premium-% reflect current market state.
 */
export function underlyingPx(opp: EnrichedOptionOpportunity): number | null {
    if (opp.liveUnderlyingPx != null && opp.liveUnderlyingPx > 0) return opp.liveUnderlyingPx;
    const fromMeta = opp.metadata?.['underlyingPx'];
    if (typeof fromMeta === 'number' && fromMeta > 0) return fromMeta;
    return null;
}

/**
 * Spot price captured at detection time. Used to display "was X / now Y"
 * comparison when the spot has drifted.
 */
export function entryUnderlyingPx(opp: EnrichedOptionOpportunity): number | null {
    const fromMeta = opp.metadata?.['underlyingPx'];
    if (typeof fromMeta === 'number' && fromMeta > 0) return fromMeta;
    return null;
}

export function premiumPctOfSpot(opp: EnrichedOptionOpportunity): number | null {
    const spot = underlyingPx(opp);
    if (!spot) return null;
    return (opp.stranglePremium / spot) * 100;
}

/**
 * Break-even moves for a long strangle/straddle, expressed as % of spot.
 * Upper BE = call_strike + premium; lower BE = put_strike - premium.
 * Returns the larger absolute distance — that's what spot has to traverse
 * for profit (whichever direction comes first).
 */
export function breakEvenPct(opp: EnrichedOptionOpportunity): number | null {
    const spot = underlyingPx(opp);
    if (!spot) return null;
    const upper = opp.strikeCall + opp.stranglePremium;
    const lower = opp.strikePut - opp.stranglePremium;
    const upperPct = ((upper - spot) / spot) * 100;
    const lowerPct = ((spot - lower) / spot) * 100;
    return Math.max(upperPct, lowerPct);
}

/**
 * Daily expected $ move implied by IV ATM (annualized). Quick rule of thumb:
 * implied_daily_move ≈ spot × IV / sqrt(365). For comparison with breakeven.
 */
export function impliedDailyMovePct(opp: EnrichedOptionOpportunity): number | null {
    if (opp.impliedVolAtm == null) return null;
    return opp.impliedVolAtm / Math.sqrt(365);
}

/**
 * "Why is this a setup?" — 1-sentence natural-language summary anchored on
 * the strongest contributor. Only uses fields the opportunity actually has;
 * silent on missing inputs (no fabricated reasoning).
 */
export function whyText(opp: EnrichedOptionOpportunity): string {
    const parts: string[] = [];
    if (opp.ivRvSpread != null && opp.realizedVol14d != null && opp.impliedVolAtm != null && opp.ivRvSpread > 0) {
        parts.push(
            `IV cheap (${opp.impliedVolAtm.toFixed(0)}% vs RV ${opp.realizedVol14d.toFixed(0)}%)`,
        );
    }
    if (opp.signalOverlay != null && opp.signalOverlay >= 50) {
        parts.push(`signal overlay ${opp.signalOverlay.toFixed(0)}/100`);
    }
    const be = breakEvenPct(opp);
    const imp = impliedDailyMovePct(opp);
    if (be != null && imp != null) {
        const dte = timeToExpiry(opp).ms / 3_600_000 / 24;
        const expectedTotalMove = imp * Math.sqrt(Math.max(dte, 0.25));
        if (expectedTotalMove > be) {
            parts.push(`implied range (${expectedTotalMove.toFixed(1)}%) exceeds break-even (${be.toFixed(1)}%)`);
        }
    }
    if (parts.length === 0) return 'Confidence above threshold but no single dominant factor.';
    return parts.join(', ') + '.';
}

/**
 * Looks up the hit-rate bucket matching this opportunity's confidence band.
 * Returns null when the bucket has insufficient samples (UI hides the strip).
 */
export function lookupHitRate(
    opp: EnrichedOptionOpportunity,
    buckets: HitRateBucket[],
    minSample = 10,
): HitRateBucket | null {
    const bucket = confidenceBucketLabel(opp.confidence);
    const hit = buckets.find(b => b.underlying === opp.underlying && b.confidenceBucket === bucket);
    if (!hit || hit.sampleSize < minSample) return null;
    return hit;
}

export function confidenceBucketLabel(confidence: number): string {
    if (confidence >= 90) return '90+';
    if (confidence >= 80) return '80-90';
    if (confidence >= 70) return '70-80';
    if (confidence >= 60) return '60-70';
    return '50-60';
}

export const VERDICT_LABEL: Record<Verdict, string> = {
    STRONG_BUY: 'STRONG BUY',
    BUY: 'BUY',
    WAIT: 'WAIT',
    SKIP: 'SKIP',
};

export type SortKey = 'confidence' | 'dte' | 'ivRvSpread' | 'premiumPct' | 'detected';

export function sortOpportunities(
    list: EnrichedOptionOpportunity[],
    key: SortKey,
): EnrichedOptionOpportunity[] {
    const sorted = [...list];
    sorted.sort((a, b) => {
        switch (key) {
            case 'confidence':
                return b.confidence - a.confidence;
            case 'dte':
                return timeToExpiry(a).ms - timeToExpiry(b).ms;
            case 'ivRvSpread':
                return (b.ivRvSpread ?? -Infinity) - (a.ivRvSpread ?? -Infinity);
            case 'premiumPct': {
                const ap = premiumPctOfSpot(a) ?? Infinity;
                const bp = premiumPctOfSpot(b) ?? Infinity;
                return ap - bp;
            }
            case 'detected':
                return new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime();
            default:
                return 0;
        }
    });
    return sorted;
}
