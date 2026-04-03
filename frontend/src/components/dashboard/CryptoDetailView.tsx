import { useParams, Link } from 'react-router-dom';
import { useState, useEffect, useRef } from 'react';
import { ArrowLeft, Loader2, TrendingUp, TrendingDown, BarChart3, Activity, Target } from 'lucide-react';
import { api } from '@/lib/api';
import { formatPrice, formatPercent, formatLargeNumber, getTrendBadgeColor, getScoreColor } from '@/lib/utils';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { CryptoDetail } from '@/types';
import { NewsFeed } from './NewsFeed';

const INTERVALS = ['1m', '5m', '15m', '1h', '4h', '1d'] as const;
const INTERVAL_LABELS: Record<string, string> = {
  '1m': '1M', '5m': '5M', '15m': '15M', '1h': '1H', '4h': '4H', '1d': '1D',
};
const INTERVAL_LIMITS: Record<string, number> = {
  '1m': 300, '5m': 200, '15m': 200, '1h': 200, '4h': 200, '1d': 365,
};

export function CryptoDetailView() {
  const { symbol } = useParams<{ symbol: string }>();
  const [detail, setDetail] = useState<CryptoDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedInterval, setSelectedInterval] = useState('1h');
  const [chartCandles, setChartCandles] = useState<any[]>([]);
  const chartContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!symbol) return;
    setLoading(true);
    api.getCryptoDetail(symbol).then((data) => {
      setDetail(data);
      if (data?.candles) setChartCandles(data.candles);
      setLoading(false);
    });
  }, [symbol]);

  // Fetch candles when interval changes
  useEffect(() => {
    if (!symbol) return;
    const limit = INTERVAL_LIMITS[selectedInterval] || 200;
    api.getCandles(symbol, selectedInterval, limit).then((data) => {
      if (data && data.length > 0) {
        setChartCandles(data);
      }
    });
  }, [symbol, selectedInterval]);

  // Initialize lightweight-charts for candle data
  useEffect(() => {
    if (!chartCandles || chartCandles.length === 0 || !chartContainerRef.current) return;

    let chart: any;
    const initChart = async () => {
      try {
        const { createChart, CrosshairMode } = await import('lightweight-charts');
        const container = chartContainerRef.current;
        if (!container) return;

        chart = createChart(container, {
          width: container.clientWidth,
          height: 400,
          layout: {
            background: { color: '#111827' },
            textColor: '#94a3b8',
            fontSize: 12,
          },
          grid: {
            vertLines: { color: '#1e293b' },
            horzLines: { color: '#1e293b' },
          },
          crosshair: {
            mode: CrosshairMode.Normal,
          },
          timeScale: {
            borderColor: '#1e293b',
            timeVisible: true,
          },
          rightPriceScale: {
            borderColor: '#1e293b',
          },
        });

        const candleSeries = chart.addCandlestickSeries({
          upColor: '#10b981',
          downColor: '#ef4444',
          borderUpColor: '#10b981',
          borderDownColor: '#ef4444',
          wickUpColor: '#10b981',
          wickDownColor: '#ef4444',
        });

        const candleData = chartCandles
          .map((c) => ({
            time: Math.floor(new Date(c.time).getTime() / 1000) as any,
            open: c.open,
            high: c.high,
            low: c.low,
            close: c.close,
          }))
          .sort((a: any, b: any) => a.time - b.time);

        candleSeries.setData(candleData);

        const volumeSeries = chart.addHistogramSeries({
          priceFormat: { type: 'volume' },
          priceScaleId: 'volume',
        });

        chart.priceScale('volume').applyOptions({
          scaleMargins: { top: 0.8, bottom: 0 },
        });

        const volumeData = chartCandles
          .map((c) => ({
            time: Math.floor(new Date(c.time).getTime() / 1000) as any,
            value: c.volume,
            color: c.close >= c.open ? 'rgba(16, 185, 129, 0.3)' : 'rgba(239, 68, 68, 0.3)',
          }))
          .sort((a: any, b: any) => a.time - b.time);

        volumeSeries.setData(volumeData);
        chart.timeScale().fitContent();

        const handleResize = () => {
          if (container) {
            chart.applyOptions({ width: container.clientWidth });
          }
        };
        window.addEventListener('resize', handleResize);

        return () => {
          window.removeEventListener('resize', handleResize);
        };
      } catch (e) {
        console.error('Chart init error:', e);
      }
    };

    initChart();

    return () => {
      if (chart) {
        chart.remove();
      }
    };
  }, [chartCandles]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <Loader2 className="h-8 w-8 text-accent animate-spin" />
      </div>
    );
  }

  if (!detail || !symbol) {
    return (
      <div className="text-center py-20">
        <p className="text-text-secondary">Failed to load data for {symbol}</p>
        <Link to="/" className="text-accent hover:underline mt-2 inline-block">Back to dashboard</Link>
      </div>
    );
  }

  const { priceData, analysis, technicalIndicators, news } = detail;
  const isPositive = priceData.priceChangePct24h >= 0;
  const name = SYMBOL_NAMES[symbol] || symbol;
  const icon = SYMBOL_ICONS[symbol] || '?';

  return (
    <div className="space-y-6">
      {/* Back + Header */}
      <div className="flex items-center gap-4">
        <Link to="/" className="text-text-secondary hover:text-accent transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </Link>
        <div className="flex items-center gap-3">
          <span className="text-3xl font-mono text-accent">{icon}</span>
          <div>
            <h1 className="text-2xl font-bold text-text-primary">{name}</h1>
            <p className="text-sm text-text-secondary">{symbol}</p>
          </div>
        </div>
        <div className="ml-auto text-right">
          <p className="text-2xl font-bold font-mono text-text-primary">{formatPrice(priceData.price)}</p>
          <p className={`text-sm font-medium ${isPositive ? 'text-gain' : 'text-loss'}`}>
            {isPositive ? <TrendingUp className="h-3.5 w-3.5 inline mr-1" /> : <TrendingDown className="h-3.5 w-3.5 inline mr-1" />}
            {formatPercent(priceData.priceChangePct24h)}
          </p>
        </div>
      </div>

      {/* Chart */}
      <div className="glass-card p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <BarChart3 className="h-4 w-4 text-accent" />
            <h2 className="text-sm font-semibold text-text-primary">Price Chart</h2>
          </div>
          <div className="flex gap-1">
            {INTERVALS.map((iv) => (
              <button
                key={iv}
                onClick={() => setSelectedInterval(iv)}
                className={`px-2.5 py-1 text-xs font-medium rounded transition-all ${
                  selectedInterval === iv
                    ? 'bg-accent text-background'
                    : 'bg-surface-light text-text-secondary hover:text-text-primary hover:bg-surface-light/80'
                }`}
              >
                {INTERVAL_LABELS[iv]}
              </button>
            ))}
          </div>
        </div>
        <div ref={chartContainerRef} className="w-full" />
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {/* Analysis Card */}
        {analysis && (
          <div className="glass-card p-5 space-y-3">
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4 text-accent" />
              <h3 className="text-sm font-semibold text-text-primary">Analysis</h3>
            </div>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <p className="text-text-secondary text-xs">Overall Score</p>
                <p className={`text-lg font-bold font-mono ${getScoreColor(analysis.overallScore)}`}>
                  {analysis.overallScore.toFixed(1)}
                </p>
              </div>
              <div>
                <p className="text-text-secondary text-xs">Trend</p>
                <span className={`inline-block text-xs font-medium px-2 py-1 rounded border mt-1 ${getTrendBadgeColor(analysis.trendDirection)}`}>
                  {analysis.trendDirection}
                </span>
              </div>
              <div>
                <p className="text-text-secondary text-xs">Sentiment</p>
                <p className={`text-lg font-bold font-mono ${getScoreColor(analysis.sentimentScore)}`}>
                  {analysis.sentimentScore.toFixed(1)}
                </p>
              </div>
              <div>
                <p className="text-text-secondary text-xs">Volume Trend</p>
                <p className="text-sm font-medium text-text-primary mt-1">{analysis.volumeTrend}</p>
              </div>
            </div>
            {analysis.signals && analysis.signals.length > 0 && (
              <div className="pt-2 border-t border-surface-border">
                <p className="text-xs text-text-secondary mb-1">Signals</p>
                <div className="flex flex-wrap gap-1">
                  {analysis.signals.map((signal, i) => (
                    <span key={i} className="text-[10px] px-1.5 py-0.5 rounded bg-accent/10 text-accent border border-accent/20">
                      {signal}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Technical Indicators */}
        {technicalIndicators && (
          <div className="glass-card p-5 space-y-3">
            <div className="flex items-center gap-2">
              <BarChart3 className="h-4 w-4 text-accent" />
              <h3 className="text-sm font-semibold text-text-primary">Technical Indicators</h3>
            </div>
            <div className="grid grid-cols-2 gap-2 text-sm">
              <IndicatorRow label="RSI (14)" value={technicalIndicators.rsi14} warn={technicalIndicators.rsi14 !== null && (technicalIndicators.rsi14 > 70 || technicalIndicators.rsi14 < 30)} />
              <IndicatorRow label="MACD" value={technicalIndicators.macdLine} />
              <IndicatorRow label="Signal" value={technicalIndicators.macdSignal} />
              <IndicatorRow label="Histogram" value={technicalIndicators.macdHistogram} />
              <IndicatorRow label="EMA 12" value={technicalIndicators.ema12} />
              <IndicatorRow label="EMA 26" value={technicalIndicators.ema26} />
              <IndicatorRow label="SMA 20" value={technicalIndicators.sma20} />
              <IndicatorRow label="SMA 50" value={technicalIndicators.sma50} />
              <IndicatorRow label="BB Upper" value={technicalIndicators.bollingerUpper} />
              <IndicatorRow label="BB Lower" value={technicalIndicators.bollingerLower} />
              <IndicatorRow label="ATR (14)" value={technicalIndicators.atr14} />
              <IndicatorRow label="SMA 200" value={technicalIndicators.sma200} />
            </div>
          </div>
        )}

        {/* Support / Resistance */}
        {analysis && (
          <div className="glass-card p-5 space-y-3">
            <div className="flex items-center gap-2">
              <Target className="h-4 w-4 text-accent" />
              <h3 className="text-sm font-semibold text-text-primary">Key Levels</h3>
            </div>
            <div className="space-y-4">
              <div>
                <p className="text-xs text-text-secondary mb-1">Resistance</p>
                <p className="text-lg font-bold font-mono text-loss">{formatPrice(analysis.resistanceLevel)}</p>
                <div className="h-1.5 bg-surface-light rounded-full mt-2 overflow-hidden">
                  <div
                    className="h-full bg-loss/60 rounded-full"
                    style={{ width: `${Math.min(100, (priceData.price / analysis.resistanceLevel) * 100)}%` }}
                  />
                </div>
              </div>
              <div className="text-center">
                <p className="text-xs text-text-secondary">Current Price</p>
                <p className="text-lg font-bold font-mono text-accent">{formatPrice(priceData.price)}</p>
              </div>
              <div>
                <p className="text-xs text-text-secondary mb-1">Support</p>
                <p className="text-lg font-bold font-mono text-gain">{formatPrice(analysis.supportLevel)}</p>
                <div className="h-1.5 bg-surface-light rounded-full mt-2 overflow-hidden">
                  <div
                    className="h-full bg-gain/60 rounded-full"
                    style={{ width: `${Math.min(100, (analysis.supportLevel / priceData.price) * 100)}%` }}
                  />
                </div>
              </div>
            </div>
            <div className="pt-3 border-t border-surface-border grid grid-cols-2 gap-3 text-sm">
              <div>
                <p className="text-xs text-text-secondary">24h Volume</p>
                <p className="font-medium font-mono text-text-primary">{formatLargeNumber(priceData.volume24h)}</p>
              </div>
              {priceData.marketCap > 0 && (
                <div>
                  <p className="text-xs text-text-secondary">Market Cap</p>
                  <p className="font-medium font-mono text-text-primary">{formatLargeNumber(priceData.marketCap)}</p>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Related News */}
      {news && news.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold text-text-primary mb-4">Related News</h2>
          <NewsFeed articles={news} />
        </section>
      )}
    </div>
  );
}

function IndicatorRow({ label, value, warn }: { label: string; value: number | null; warn?: boolean }) {
  if (value === null || value === undefined) {
    return (
      <div className="flex justify-between items-center py-1">
        <span className="text-text-secondary text-xs">{label}</span>
        <span className="text-text-secondary text-xs">--</span>
      </div>
    );
  }

  const formatted = Math.abs(value) >= 1000
    ? value.toLocaleString('en-US', { maximumFractionDigits: 2 })
    : value.toFixed(4);

  return (
    <div className="flex justify-between items-center py-1">
      <span className="text-text-secondary text-xs">{label}</span>
      <span className={`text-xs font-mono font-medium ${warn ? 'text-yellow-400' : 'text-text-primary'}`}>
        {formatted}
      </span>
    </div>
  );
}
