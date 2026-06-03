import { useCallback, useEffect, useMemo, useState } from 'react';
import { Loader2, TrendingUp, RefreshCw } from 'lucide-react';
import { api } from '@/lib/api';
import type { EnrichedOptionOpportunity, HitRateBucket } from '@/types';
import { cn } from '@/lib/utils';
import {
    classifyStrategy,
    DTE_BUCKET_LABEL,
    dteBucket,
    OPTIONS_CONFIDENCE_THRESHOLD,
    sortOpportunities,
    type DteBucket,
    type SortKey,
    type StrategyKind,
} from '@/lib/optionsDerivations';
import { OpportunityCard } from './OpportunityCard';
import { VolGapPanel } from './VolGapPanel';
import { SortControls } from './SortControls';
import { UnderlyingFilter } from './UnderlyingFilter';
import { GroupingToggle, type GroupMode } from './GroupingToggle';

const REFRESH_INTERVAL_MS = 30_000;
const ALL_UNDERLYINGS = ['BTC', 'ETH', 'SOL', 'XAUT', 'XRP', 'MNT', 'DOGE'];

export function OptionsPage() {
    const [opportunities, setOpportunities] = useState<EnrichedOptionOpportunity[]>([]);
    const [hitRate, setHitRate] = useState<HitRateBucket[]>([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [lastRefresh, setLastRefresh] = useState<Date | null>(null);

    const [sortKey, setSortKey] = useState<SortKey>('confidence');
    const [groupMode, setGroupMode] = useState<GroupMode>('none');
    const [selectedUnderlyings, setSelectedUnderlyings] = useState<Set<string>>(
        new Set(ALL_UNDERLYINGS),
    );
    const [showStale, setShowStale] = useState(false);

    const load = useCallback(async (initial: boolean) => {
        if (!initial) setRefreshing(true);
        const [opps, hits] = await Promise.all([
            api.getOptionsOpportunitiesEnriched(50, true, showStale),
            api.getOptionsHitRate(),
        ]);
        if (opps) setOpportunities(opps);
        if (hits) setHitRate(hits);
        setLoading(false);
        setRefreshing(false);
        setLastRefresh(new Date());
    }, [showStale]);

    useEffect(() => {
        load(true);
        const interval = setInterval(() => load(false), REFRESH_INTERVAL_MS);
        return () => clearInterval(interval);
    }, [load]);

    const visible = useMemo(() => {
        const filtered = opportunities.filter((o) => selectedUnderlyings.has(o.underlying));
        return sortOpportunities(filtered, sortKey);
    }, [opportunities, selectedUnderlyings, sortKey]);

    const groups = useMemo(() => {
        if (groupMode === 'none') return null;
        return groupOpportunities(visible, groupMode);
    }, [visible, groupMode]);

    const toggleUnderlying = (u: string) => {
        setSelectedUnderlyings((prev) => {
            const next = new Set(prev);
            if (next.has(u)) next.delete(u); else next.add(u);
            if (next.size === 0) return new Set(ALL_UNDERLYINGS); // never empty
            return next;
        });
    };

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between flex-wrap gap-2">
                <div className="flex items-center gap-2">
                    <TrendingUp className="h-5 w-5 text-accent" />
                    <h1 className="text-xl font-bold text-text-primary">Options Volatility Watchlist</h1>
                    <span className="text-[10px] text-text-secondary">
                        (Bybit 0-3d strangle/straddle entries · live every 60s)
                    </span>
                </div>
                <button
                    onClick={() => load(false)}
                    disabled={refreshing}
                    className="flex items-center gap-1.5 px-3 py-1 text-xs rounded border border-surface-border text-text-secondary hover:text-accent hover:border-accent/40 transition-colors disabled:opacity-50"
                >
                    <RefreshCw className={cn('h-3 w-3', refreshing && 'animate-spin')} />
                    {refreshing
                        ? 'Refreshing…'
                        : lastRefresh
                          ? `Refreshed ${lastRefresh.toLocaleTimeString()}`
                          : 'Refresh'}
                </button>
            </div>

            <VolGapPanel />

            <div className="glass-card p-3 flex flex-wrap items-center gap-x-6 gap-y-2">
                <SortControls value={sortKey} onChange={setSortKey} />
                <UnderlyingFilter
                    selected={selectedUnderlyings}
                    onToggle={toggleUnderlying}
                    onReset={() => setSelectedUnderlyings(new Set(ALL_UNDERLYINGS))}
                />
                <GroupingToggle value={groupMode} onChange={setGroupMode} />
                <label
                    className="inline-flex items-center gap-1.5 text-[10px] text-text-secondary uppercase tracking-wide cursor-pointer"
                    title="Show opportunities whose live confidence has dropped below threshold or whose strike band no longer brackets spot."
                >
                    <input
                        type="checkbox"
                        checked={showStale}
                        onChange={(e) => setShowStale(e.target.checked)}
                        className="h-3 w-3 accent-[var(--color-accent)]"
                    />
                    Show stale
                </label>
            </div>

            <div>
                <h2 className="text-xs font-semibold text-text-secondary uppercase tracking-wider mb-3">
                    Open Opportunities ({visible.length}
                    {visible.length !== opportunities.length ? ` of ${opportunities.length}` : ''})
                </h2>
                {loading ? (
                    <div className="flex items-center justify-center h-32">
                        <Loader2 className="h-6 w-6 text-accent animate-spin" />
                    </div>
                ) : visible.length === 0 ? (
                    <EmptyState totalCount={opportunities.length} />
                ) : groups ? (
                    <GroupedGrid groups={groups} hitRate={hitRate} />
                ) : (
                    <FlatGrid items={visible} hitRate={hitRate} />
                )}
            </div>
        </div>
    );
}

function EmptyState({ totalCount }: { totalCount: number }) {
    if (totalCount === 0) {
        return (
            <div className="glass-card p-8 text-sm text-text-secondary text-center">
                No active opportunities — the scorer fires only when implied vol is materially below
                realized vol AND signal-engine activity supports a vol-expansion thesis.
                <br />
                <span className="text-[10px]">Confidence threshold currently {OPTIONS_CONFIDENCE_THRESHOLD.toFixed(1)} / 100.</span>
            </div>
        );
    }
    return (
        <div className="glass-card p-8 text-sm text-text-secondary text-center">
            No opportunities match the current filter. Try toggling more underlyings.
        </div>
    );
}

function FlatGrid({ items, hitRate }: { items: EnrichedOptionOpportunity[]; hitRate: HitRateBucket[] }) {
    return (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {items.map((opp) => (
                <OpportunityCard key={opp.id} opportunity={opp} hitRateBuckets={hitRate} />
            ))}
        </div>
    );
}

function GroupedGrid({
    groups,
    hitRate,
}: {
    groups: { title: string; items: EnrichedOptionOpportunity[] }[];
    hitRate: HitRateBucket[];
}) {
    return (
        <div className="space-y-4">
            {groups.map((g) => (
                <section key={g.title} className="space-y-2">
                    <h3 className="text-[11px] font-semibold text-text-secondary uppercase tracking-wider border-b border-surface-border pb-1">
                        {g.title} · {g.items.length}
                    </h3>
                    <FlatGrid items={g.items} hitRate={hitRate} />
                </section>
            ))}
        </div>
    );
}

const STRATEGY_ORDER: StrategyKind[] = ['STRADDLE', 'STRANGLE'];
const DTE_ORDER: DteBucket[] = ['today', 'tomorrow', 'two_three', 'four_plus'];

function groupOpportunities(
    items: EnrichedOptionOpportunity[],
    mode: GroupMode,
): { title: string; items: EnrichedOptionOpportunity[] }[] {
    if (mode === 'strategy') {
        const buckets = new Map<StrategyKind, EnrichedOptionOpportunity[]>();
        for (const o of items) {
            const k = classifyStrategy(o);
            const arr = buckets.get(k) ?? [];
            arr.push(o);
            buckets.set(k, arr);
        }
        return STRATEGY_ORDER
            .filter((k) => buckets.has(k))
            .map((k) => ({ title: k, items: buckets.get(k)! }));
    }
    if (mode === 'dte') {
        const buckets = new Map<DteBucket, EnrichedOptionOpportunity[]>();
        for (const o of items) {
            const k = dteBucket(o);
            const arr = buckets.get(k) ?? [];
            arr.push(o);
            buckets.set(k, arr);
        }
        return DTE_ORDER
            .filter((k) => buckets.has(k))
            .map((k) => ({ title: DTE_BUCKET_LABEL[k], items: buckets.get(k)! }));
    }
    return [{ title: 'All', items }];
}
