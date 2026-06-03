import type { BacktestTrade } from '@/types';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  ReferenceLine,
} from 'recharts';

interface EquityCurveChartProps {
  trades: BacktestTrade[];
  originalTotalR: number;
}

interface CurvePoint {
  index: number;
  backtest: number;
  original: number;
}

function buildCurveData(trades: BacktestTrade[], originalTotalR: number): CurvePoint[] {
  let backtestCumulative = 0;
  const points: CurvePoint[] = [{ index: 0, backtest: 0, original: 0 }];

  // Distribute original R linearly across trades for the baseline curve
  const originalPerTrade = trades.length > 0 ? originalTotalR / trades.length : 0;
  let originalCumulative = 0;

  trades.forEach((trade, i) => {
    backtestCumulative += trade.contributedR;
    originalCumulative += originalPerTrade;
    points.push({
      index: i + 1,
      backtest: Math.round(backtestCumulative * 100) / 100,
      original: Math.round(originalCumulative * 100) / 100,
    });
  });

  return points;
}

function formatR(value: number): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}R`;
}

export function EquityCurveChart({ trades, originalTotalR }: EquityCurveChartProps) {
  if (trades.length === 0) {
    return (
      <div className="glass-card p-4 flex items-center justify-center h-48">
        <span className="text-sm text-text-secondary">No trades to chart</span>
      </div>
    );
  }

  const data = buildCurveData(trades, originalTotalR);

  return (
    <div className="glass-card p-4">
      <h3 className="text-sm font-semibold text-text-primary mb-4">Equity Curve (Cumulative R)</h3>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 4, right: 12, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
          <XAxis
            dataKey="index"
            tick={{ fontSize: 10, fill: 'var(--color-text-secondary, #888)' }}
            tickLine={false}
            axisLine={false}
            label={{ value: 'Trade #', position: 'insideBottomRight', offset: -4, fontSize: 10, fill: 'var(--color-text-secondary, #888)' }}
          />
          <YAxis
            tick={{ fontSize: 10, fill: 'var(--color-text-secondary, #888)' }}
            tickLine={false}
            axisLine={false}
            tickFormatter={formatR}
            width={52}
          />
          <Tooltip
            contentStyle={{
              background: 'rgba(15,15,25,0.95)',
              border: '1px solid rgba(255,255,255,0.1)',
              borderRadius: '6px',
              fontSize: '12px',
            }}
            labelStyle={{ color: '#aaa' }}
            formatter={(value: unknown, name: unknown) => {
              const numVal = typeof value === 'number' ? value : 0;
              const strName = String(name);
              return [formatR(numVal), strName === 'backtest' ? 'Backtest' : 'Original'] as [string, string];
            }}
            labelFormatter={(label: unknown) => `Trade ${label}`}
          />
          <ReferenceLine y={0} stroke="rgba(255,255,255,0.15)" strokeDasharray="4 4" />
          <Line
            type="monotone"
            dataKey="original"
            stroke="rgba(255,255,255,0.3)"
            strokeWidth={1.5}
            dot={false}
            strokeDasharray="5 3"
            name="original"
          />
          <Line
            type="monotone"
            dataKey="backtest"
            stroke="var(--color-accent, #6366f1)"
            strokeWidth={2}
            dot={false}
            name="backtest"
          />
        </LineChart>
      </ResponsiveContainer>
      <div className="flex items-center gap-4 mt-2">
        <div className="flex items-center gap-1.5">
          <div className="w-4 h-0.5 bg-accent rounded" />
          <span className="text-[10px] text-text-secondary">Backtest</span>
        </div>
        <div className="flex items-center gap-1.5">
          <div className="w-4 h-px bg-white/30 border-dashed" style={{ borderTop: '1px dashed rgba(255,255,255,0.3)' }} />
          <span className="text-[10px] text-text-secondary">Original (linear)</span>
        </div>
      </div>
    </div>
  );
}
