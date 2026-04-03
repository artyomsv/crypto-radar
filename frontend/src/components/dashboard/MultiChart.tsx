import { useState, useEffect, useRef, useCallback } from 'react';
import { Loader2, Columns, Grid2X2, ChevronDown } from 'lucide-react';
import { api } from '@/lib/api';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import { formatPrice, formatPercent } from '@/lib/utils';
import { useWebSocket } from '@/hooks/useWebSocket';

const INTERVALS = ['1m', '5m', '15m', '1h', '4h', '1d'] as const;
const INTERVAL_LABELS: Record<string, string> = {
  '1m': '1m', '5m': '5m', '15m': '15m', '1h': '1H', '4h': '4H', '1d': '1D',
};
const INTERVAL_LIMITS: Record<string, number> = {
  '1m': 200, '5m': 200, '15m': 200, '1h': 200, '4h': 200, '1d': 365,
};

const DEFAULT_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'XRPUSDT'];
const ALL_SYMBOLS = Object.keys(SYMBOL_NAMES);

type LayoutMode = '2x2' | '1x2';

export function MultiChart() {
  const [selectedSymbols, setSelectedSymbols] = useState<string[]>(DEFAULT_SYMBOLS);
  const [interval, setInterval] = useState('1h');
  const [layout, setLayout] = useState<LayoutMode>('2x2');
  const [livePrices, setLivePrices] = useState<Record<string, { price: number; changePct: number }>>({});

  const visibleSymbols = layout === '1x2' ? selectedSymbols.slice(0, 2) : selectedSymbols.slice(0, 4);

  useWebSocket({
    onPrices: (prices: any[]) => {
      if (!prices || !Array.isArray(prices)) return;
      setLivePrices(prev => {
        const next = { ...prev };
        for (const p of prices) {
          if (p.symbol && p.price) {
            next[p.symbol] = { price: p.price, changePct: p.priceChangePct24h ?? 0 };
          }
        }
        return next;
      });
    },
  });

  const handleSymbolChange = useCallback((index: number, symbol: string) => {
    setSelectedSymbols(prev => {
      const next = [...prev];
      next[index] = symbol;
      return next;
    });
  }, []);

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <Columns className="h-5 w-5 text-accent" />
          <h1 className="text-xl font-bold text-text-primary">Multi-Chart Comparison</h1>
        </div>
        <div className="flex items-center gap-3">
          {/* Interval selector */}
          <div className="flex gap-1">
            {INTERVALS.map(iv => (
              <button
                key={iv}
                onClick={() => setInterval(iv)}
                className={`px-2.5 py-1 text-xs font-medium rounded transition-all ${
                  interval === iv
                    ? 'bg-accent text-background'
                    : 'bg-surface-light text-text-secondary hover:text-text-primary hover:bg-surface-light/80'
                }`}
              >
                {INTERVAL_LABELS[iv]}
              </button>
            ))}
          </div>
          {/* Layout toggle */}
          <div className="flex gap-1 bg-surface-light rounded p-0.5">
            <button
              onClick={() => setLayout('1x2')}
              className={`p-1.5 rounded transition-all ${
                layout === '1x2' ? 'bg-accent text-background' : 'text-text-secondary hover:text-text-primary'
              }`}
              title="Side by side"
            >
              <Columns className="h-4 w-4" />
            </button>
            <button
              onClick={() => setLayout('2x2')}
              className={`p-1.5 rounded transition-all ${
                layout === '2x2' ? 'bg-accent text-background' : 'text-text-secondary hover:text-text-primary'
              }`}
              title="2x2 grid"
            >
              <Grid2X2 className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Chart Grid */}
      <div className={`grid gap-4 ${layout === '2x2' ? 'grid-cols-1 md:grid-cols-2' : 'grid-cols-1 md:grid-cols-2'}`}>
        {visibleSymbols.map((symbol, index) => (
          <ChartCell
            key={`${symbol}-${index}`}
            symbol={symbol}
            index={index}
            interval={interval}
            livePrice={livePrices[symbol]}
            onSymbolChange={handleSymbolChange}
          />
        ))}
      </div>
    </div>
  );
}

// --- Symbol Dropdown ---

interface SymbolDropdownProps {
  value: string;
  onChange: (symbol: string) => void;
}

function SymbolDropdown({ value, onChange }: SymbolDropdownProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1.5 px-2.5 py-1 text-sm font-medium rounded bg-surface-light hover:bg-surface-light/80 text-text-primary border border-surface-border transition-colors"
      >
        <span className="text-accent font-mono">{SYMBOL_ICONS[value] || '?'}</span>
        <span>{SYMBOL_NAMES[value] || value}</span>
        <ChevronDown className="h-3.5 w-3.5 text-text-secondary" />
      </button>
      {open && (
        <div className="absolute top-full left-0 mt-1 z-50 bg-surface border border-surface-border rounded-lg shadow-xl py-1 min-w-[180px] max-h-[300px] overflow-y-auto">
          {ALL_SYMBOLS.map(sym => (
            <button
              key={sym}
              onClick={() => { onChange(sym); setOpen(false); }}
              className={`w-full text-left px-3 py-1.5 text-sm flex items-center gap-2 transition-colors ${
                sym === value
                  ? 'bg-accent/10 text-accent'
                  : 'text-text-primary hover:bg-surface-light'
              }`}
            >
              <span className="font-mono text-accent w-5">{SYMBOL_ICONS[sym]}</span>
              <span>{SYMBOL_NAMES[sym]}</span>
              <span className="text-text-secondary text-xs ml-auto">{sym.replace('USDT', '')}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// --- Chart Cell ---

interface ChartCellProps {
  symbol: string;
  index: number;
  interval: string;
  livePrice?: { price: number; changePct: number };
  onSymbolChange: (index: number, symbol: string) => void;
}

function ChartCell({ symbol, index, interval, livePrice, onSymbolChange }: ChartCellProps) {
  const [loading, setLoading] = useState(true);
  const [candles, setCandles] = useState<any[]>([]);
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<any>(null);
  const candleSeriesRef = useRef<any>(null);

  // Fetch candle data when symbol or interval changes
  useEffect(() => {
    setLoading(true);
    const limit = INTERVAL_LIMITS[interval] || 200;
    api.getCandles(symbol, interval, limit).then(data => {
      if (data && data.length > 0) {
        setCandles(data);
      }
      setLoading(false);
    });
  }, [symbol, interval]);

  // Update live candle
  useEffect(() => {
    if (!livePrice || !candleSeriesRef.current) return;
    const now = Math.floor(Date.now() / 1000);
    const intervalSeconds: Record<string, number> = {
      '1m': 60, '5m': 300, '15m': 900, '1h': 3600, '4h': 14400, '1d': 86400,
    };
    const bucket = intervalSeconds[interval] || 3600;
    const candleTime = Math.floor(now / bucket) * bucket;

    candleSeriesRef.current.update({
      time: candleTime as any,
      open: livePrice.price,
      high: livePrice.price,
      low: livePrice.price,
      close: livePrice.price,
    });
  }, [livePrice, interval]);

  // Initialize chart
  useEffect(() => {
    if (!candles || candles.length === 0 || !chartContainerRef.current) return;

    if (chartRef.current) {
      chartRef.current.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
    }

    let cancelled = false;
    const initChart = async () => {
      try {
        const { createChart, CrosshairMode } = await import('lightweight-charts');
        if (cancelled) return;
        const container = chartContainerRef.current;
        if (!container) return;

        const chart = createChart(container, {
          width: container.clientWidth,
          height: 300,
          layout: {
            background: { color: '#111827' },
            textColor: '#94a3b8',
            fontSize: 11,
          },
          grid: {
            vertLines: { color: '#1e293b' },
            horzLines: { color: '#1e293b' },
          },
          crosshair: { mode: CrosshairMode.Normal },
          timeScale: { borderColor: '#1e293b', timeVisible: true },
          rightPriceScale: { borderColor: '#1e293b' },
        });

        const candleSeries = chart.addCandlestickSeries({
          upColor: '#10b981',
          downColor: '#ef4444',
          borderUpColor: '#10b981',
          borderDownColor: '#ef4444',
          wickUpColor: '#10b981',
          wickDownColor: '#ef4444',
        });

        const candleData = candles
          .map(c => ({
            time: Math.floor(new Date(c.time).getTime() / 1000) as any,
            open: c.open,
            high: c.high,
            low: c.low,
            close: c.close,
          }))
          .sort((a: any, b: any) => a.time - b.time);

        candleSeries.setData(candleData);
        candleSeriesRef.current = candleSeries;
        chartRef.current = chart;

        // Volume histogram
        const volumeSeries = chart.addHistogramSeries({
          priceFormat: { type: 'volume' },
          priceScaleId: 'volume',
        });
        chart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.85, bottom: 0 } });

        const volumeData = candles
          .map(c => ({
            time: Math.floor(new Date(c.time).getTime() / 1000) as any,
            value: c.volume,
            color: c.close >= c.open ? 'rgba(16, 185, 129, 0.2)' : 'rgba(239, 68, 68, 0.2)',
          }))
          .sort((a: any, b: any) => a.time - b.time);

        volumeSeries.setData(volumeData);
        chart.timeScale().fitContent();

        const handleResize = () => {
          if (container) chart.applyOptions({ width: container.clientWidth });
        };
        window.addEventListener('resize', handleResize);

        return () => window.removeEventListener('resize', handleResize);
      } catch (e) {
        console.error('Chart init error:', e);
      }
    };

    initChart();

    return () => {
      cancelled = true;
      candleSeriesRef.current = null;
      if (chartRef.current) {
        chartRef.current.remove();
        chartRef.current = null;
      }
    };
  }, [candles]);

  const changePct = livePrice?.changePct ?? 0;
  const isPositive = changePct >= 0;

  return (
    <div className="glass-card p-3">
      {/* Header with selector and price */}
      <div className="flex items-center justify-between mb-2">
        <SymbolDropdown
          value={symbol}
          onChange={sym => onSymbolChange(index, sym)}
        />
        <div className="flex items-center gap-2">
          {livePrice && (
            <>
              <span className="text-sm font-bold font-mono text-text-primary">
                {formatPrice(livePrice.price)}
              </span>
              <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${
                isPositive ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'
              }`}>
                {formatPercent(changePct)}
              </span>
            </>
          )}
        </div>
      </div>

      {/* Chart */}
      {loading ? (
        <div className="flex items-center justify-center h-[300px]">
          <Loader2 className="h-6 w-6 text-accent animate-spin" />
        </div>
      ) : (
        <div ref={chartContainerRef} className="w-full" />
      )}
    </div>
  );
}
