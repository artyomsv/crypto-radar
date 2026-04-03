import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/api';
import { formatLargeNumber } from '@/lib/utils';
import { Loader2, Globe, TrendingUp, Landmark, DollarSign, Layers } from 'lucide-react';
import type { MacroOverview } from '@/types';

function DominanceBar({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="text-xs text-text-secondary">{label}</span>
        <span className={`text-sm font-mono font-bold ${color}`}>{value.toFixed(1)}%</span>
      </div>
      <div className="h-2.5 bg-surface-light rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-700 ${
            color === 'text-orange-400' ? 'bg-orange-400/80' : 'bg-blue-400/80'
          }`}
          style={{ width: `${Math.min(value, 100)}%` }}
        />
      </div>
    </div>
  );
}

function MetricCard({
  icon: Icon,
  label,
  value,
  sub,
}: {
  icon: any;
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <div className="glass-card p-4 space-y-1">
      <div className="flex items-center gap-2 text-text-secondary">
        <Icon className="h-4 w-4 text-accent" />
        <span className="text-xs">{label}</span>
      </div>
      <p className="text-lg font-bold font-mono text-text-primary">{value}</p>
      {sub && <p className="text-[10px] text-text-secondary">{sub}</p>}
    </div>
  );
}

export function MacroIndicators() {
  const [data, setData] = useState<MacroOverview | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    const result = await api.getMacroOverview();
    if (result) setData(result);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 300_000); // 5 min
    return () => clearInterval(interval);
  }, [fetchData]);

  if (loading) {
    return (
      <div className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Globe className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Macro Indicators</h2>
        </div>
        <div className="flex items-center justify-center h-48">
          <Loader2 className="h-6 w-6 text-accent animate-spin" />
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Globe className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Macro Indicators</h2>
        </div>
        <p className="text-text-secondary text-sm text-center py-8">
          Macro data unavailable. External APIs may be rate-limited.
        </p>
      </div>
    );
  }

  return (
    <div className="glass-card p-5 space-y-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Globe className="h-5 w-5 text-accent" />
          <h2 className="text-lg font-semibold text-text-primary">Macro Indicators</h2>
        </div>
        <span className="text-[10px] text-text-secondary">
          Updated {new Date(data.timestamp).toLocaleTimeString()}
        </span>
      </div>

      {/* Dominance Bars */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <DominanceBar label="BTC Dominance" value={data.btcDominance} color="text-orange-400" />
        <DominanceBar label="ETH Dominance" value={data.ethDominance} color="text-blue-400" />
      </div>

      {/* Metric Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <MetricCard
          icon={TrendingUp}
          label="Total Market Cap"
          value={formatLargeNumber(data.totalMarketCapUsd)}
        />
        <MetricCard
          icon={DollarSign}
          label="USDT Market Cap"
          value={formatLargeNumber(data.usdtMarketCap)}
        />
        <MetricCard
          icon={DollarSign}
          label="USDC Market Cap"
          value={formatLargeNumber(data.usdcMarketCap)}
        />
        <MetricCard
          icon={Landmark}
          label="Stablecoin Supply"
          value={formatLargeNumber(data.totalStablecoinCap)}
          sub={`${((data.totalStablecoinCap / data.totalMarketCapUsd) * 100).toFixed(1)}% of total market`}
        />
      </div>

      {/* DeFi TVL */}
      <div className="flex items-center justify-between p-3 bg-surface-light/30 rounded-lg border border-surface-border">
        <div className="flex items-center gap-2">
          <Layers className="h-4 w-4 text-accent" />
          <span className="text-sm text-text-secondary">DeFi Total Value Locked</span>
        </div>
        <span className="text-lg font-bold font-mono text-accent">
          {formatLargeNumber(data.defiTvlUsd)}
        </span>
      </div>
    </div>
  );
}
