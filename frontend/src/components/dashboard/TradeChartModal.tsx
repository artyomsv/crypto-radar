import { useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { formatPrice, formatTimeAgo } from '@/lib/utils';
import {
  ArrowDownRight,
  ArrowUpRight,
  Loader2,
  X,
  Zap,
} from 'lucide-react';
import type { SignalOutcomeView } from '@/types';

const CHART_INTERVAL = '1h';
const CHART_CANDLE_LIMIT = 250;

const ENTRY_LONG_COLOR = '#10b981';
const ENTRY_SHORT_COLOR = '#ef4444';
const EXIT_WIN_COLOR = '#10b981';
const EXIT_LOSS_COLOR = '#ef4444';
const EXIT_EXPIRED_COLOR = '#64748b';
const HIGHLIGHT_HALO = '#facc15';

interface TradeChartModalProps {
  symbol: string;
  highlightedSignalId: string;
  onClose: () => void;
  singleOutcomeOnly?: boolean;
}

interface CandleRow {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
}

function isoToUnix(iso: string): number {
  return Math.floor(new Date(iso).getTime() / 1000);
}

function formatRealizedR(value: number | null): string {
  if (value === null) return 'open';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}R`;
}

function exitColor(status: SignalOutcomeView['status']): string {
  if (status === 'HIT_TARGET') return EXIT_WIN_COLOR;
  if (status === 'HIT_STOP') return EXIT_LOSS_COLOR;
  return EXIT_EXPIRED_COLOR;
}

function buildMarkersForOutcome(outcome: SignalOutcomeView, isHighlighted: boolean) {
  const isLong = outcome.direction === 'LONG';
  const entryColor = isHighlighted ? HIGHLIGHT_HALO : (isLong ? ENTRY_LONG_COLOR : ENTRY_SHORT_COLOR);
  const entryMarker = {
    time: isoToUnix(outcome.firedAt) as never,
    position: (isLong ? 'belowBar' : 'aboveBar') as 'belowBar' | 'aboveBar',
    color: entryColor,
    shape: (isLong ? 'arrowUp' : 'arrowDown') as 'arrowUp' | 'arrowDown',
    text: `${outcome.signalType} ${outcome.alignment}%`,
  };

  if (!outcome.closedAt) return [entryMarker];

  const exitMarker = {
    time: isoToUnix(outcome.closedAt) as never,
    position: (isLong ? 'aboveBar' : 'belowBar') as 'belowBar' | 'aboveBar',
    color: isHighlighted ? HIGHLIGHT_HALO : exitColor(outcome.status),
    shape: 'circle' as const,
    text: formatRealizedR(outcome.realizedRMultiple),
  };
  return [entryMarker, exitMarker];
}

interface TradeSummaryProps {
  outcome: SignalOutcomeView;
}

function TradeSummary({ outcome }: TradeSummaryProps) {
  const isLong = outcome.direction === 'LONG';
  const DirIcon = isLong ? ArrowUpRight : ArrowDownRight;
  return (
    <div className="glass-card p-3 text-xs text-text-secondary flex items-center flex-wrap gap-x-4 gap-y-1">
      <span className={`flex items-center gap-1 font-semibold ${isLong ? 'text-gain' : 'text-loss'}`}>
        <DirIcon className="h-3 w-3" />
        {outcome.direction}
      </span>
      <span>Strategy <span className="text-text-primary font-medium">{outcome.strategy}</span></span>
      <span>Entry <span className="text-text-primary font-mono">{formatPrice(outcome.entryPrice)}</span></span>
      <span>Stop <span className="text-loss font-mono">{formatPrice(outcome.stopPrice)}</span></span>
      <span>Target <span className="text-gain font-mono">{formatPrice(outcome.targetPrice)}</span></span>
      <span>R:R <span className="text-text-primary">{outcome.riskRewardRatio.toFixed(2)}</span></span>
      <span>Fired <span className="text-text-primary">{formatTimeAgo(outcome.firedAt)}</span></span>
      <span>Status <span className="text-text-primary">{outcome.status}</span></span>
      {outcome.realizedRMultiple !== null && (
        <span>
          Realized{' '}
          <span className={outcome.realizedRMultiple > 0 ? 'text-gain font-semibold' : 'text-loss font-semibold'}>
            {formatRealizedR(outcome.realizedRMultiple)}
          </span>
        </span>
      )}
    </div>
  );
}

function Legend() {
  return (
    <div className="text-[10px] text-muted flex flex-wrap items-center gap-3 px-2">
      <span className="flex items-center gap-1">
        <span className="inline-block w-2 h-2 rotate-45 border-l-2 border-b-2 border-gain" /> LONG entry
      </span>
      <span className="flex items-center gap-1">
        <span className="inline-block w-2 h-2 rotate-[-135deg] border-l-2 border-b-2 border-loss" /> SHORT entry
      </span>
      <span className="flex items-center gap-1">
        <span className="inline-block w-2 h-2 rounded-full bg-gain" /> Target hit
      </span>
      <span className="flex items-center gap-1">
        <span className="inline-block w-2 h-2 rounded-full bg-loss" /> Stop hit
      </span>
      <span className="flex items-center gap-1">
        <span className="inline-block w-2 h-2 rounded-full" style={{ background: HIGHLIGHT_HALO }} /> Selected trade
      </span>
    </div>
  );
}

export function TradeChartModal({ symbol, highlightedSignalId, onClose, singleOutcomeOnly = false }: TradeChartModalProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const [outcomes, setOutcomes] = useState<SignalOutcomeView[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const highlighted = outcomes.find((o) => o.signalId === highlightedSignalId);

  // Close on Escape
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose]);

  // Initialize chart once data arrives. fetch+render in a single effect so
  // we capture candles and outcomes as local vars (no stale closure against
  // React state).
  useEffect(() => {
    let cancelled = false;
    let chartRemover: (() => void) | null = null;

    const init = async () => {
      setLoading(true);
      setErrorMsg(null);
      const [candles, outcomeList] = await Promise.all([
        api.getCandles(symbol, CHART_INTERVAL, CHART_CANDLE_LIMIT) as Promise<CandleRow[] | null>,
        api.getSignalOutcomes(symbol, 100),
      ]);
      if (cancelled) return;
      const fetchedOutcomes = outcomeList ?? [];
      setOutcomes(fetchedOutcomes);

      if (!candles || candles.length === 0) {
        setErrorMsg('No candle data for this symbol.');
        setLoading(false);
        return;
      }

      const { createChart, CrosshairMode } = await import('lightweight-charts');
      if (cancelled) return;
      const container = chartContainerRef.current;
      if (!container) return;

      // Guard against the container having zero width at chart-init time —
      // happens if the element is still hidden or the modal hasn't finished
      // its enter transition. Fall back to a sensible default; the
      // ResizeObserver below will fix it on the next paint.
      const initialWidth = container.clientWidth || 900;
      const chart = createChart(container, {
        width: initialWidth,
        height: 460,
        layout: { background: { color: '#111827' }, textColor: '#94a3b8', fontSize: 12 },
        grid:   { vertLines: { color: '#1e293b' }, horzLines: { color: '#1e293b' } },
        crosshair: { mode: CrosshairMode.Normal },
        timeScale: { borderColor: '#1e293b', timeVisible: true },
        rightPriceScale: { borderColor: '#1e293b' },
      });

      const candleSeries = chart.addCandlestickSeries({
        upColor: '#10b981', downColor: '#ef4444',
        borderUpColor: '#10b981', borderDownColor: '#ef4444',
        wickUpColor: '#10b981', wickDownColor: '#ef4444',
      });

      const candleData = candles
        .map((c) => ({
          time: isoToUnix(c.time) as never,
          open: Number(c.open), high: Number(c.high),
          low: Number(c.low),  close: Number(c.close),
        }))
        .filter((c) => isFinite(c.open) && isFinite(c.close))
        .sort((a, b) => (a.time as unknown as number) - (b.time as unknown as number));
      candleSeries.setData(candleData);

      const markerSource = singleOutcomeOnly
        ? fetchedOutcomes.filter((o) => o.signalId === highlightedSignalId)
        : fetchedOutcomes;
      const markers = markerSource
        .flatMap((o) => buildMarkersForOutcome(o, o.signalId === highlightedSignalId))
        .sort((a, b) => (a.time as unknown as number) - (b.time as unknown as number));
      candleSeries.setMarkers(markers);

      chart.timeScale().fitContent();
      setLoading(false);

      // ResizeObserver keeps the chart width in sync with the container after
      // any subsequent layout change (modal transitions, window resize,
      // devtools panel toggle). This also auto-repairs the chart if it was
      // initialized while the container had zero width.
      const resizeObserver = new ResizeObserver(() => {
        const width = container.clientWidth;
        if (width > 0) chart.applyOptions({ width });
      });
      resizeObserver.observe(container);

      const onWindowResize = () => chart.applyOptions({ width: container.clientWidth || initialWidth });
      window.addEventListener('resize', onWindowResize);

      chartRemover = () => {
        resizeObserver.disconnect();
        window.removeEventListener('resize', onWindowResize);
        chart.remove();
      };
    };

    init();
    return () => { cancelled = true; chartRemover?.(); };
  }, [symbol, highlightedSignalId, singleOutcomeOnly]);

  return (
    <div
      className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="glass-card w-full max-w-5xl max-h-[92vh] overflow-y-auto p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h3 className="text-base font-bold text-text-primary flex items-center gap-2">
            <Zap className="h-4 w-4 text-accent" />
            {symbol.replace('USDT', '')}/USDT · Trade Chart
          </h3>
          <button
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary transition-colors"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {highlighted && <TradeSummary outcome={highlighted} />}

        {/*
          The chart container stays mounted and sized ALWAYS, even during
          loading. Overlays cover it with a spinner / error state instead of
          hiding it. This guarantees lightweight-charts sees a non-zero
          clientWidth when it initializes — otherwise it creates zero-width
          canvases that stay invisible forever.
        */}
        <div className="relative w-full min-h-[460px]">
          <div ref={chartContainerRef} className="w-full h-[460px]" />

          {loading && (
            <div className="absolute inset-0 flex items-center justify-center bg-surface/70 backdrop-blur-sm rounded">
              <Loader2 className="h-5 w-5 text-accent animate-spin mr-2" />
              <span className="text-sm text-text-secondary">Loading chart…</span>
            </div>
          )}

          {errorMsg && !loading && (
            <div className="absolute inset-0 flex items-center justify-center text-sm text-loss bg-surface/70 backdrop-blur-sm rounded">
              {errorMsg}
            </div>
          )}
        </div>

        <Legend />
      </div>
    </div>
  );
}
