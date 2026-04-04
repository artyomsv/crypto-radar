import { useState, useEffect, useMemo } from 'react';
import { api } from '@/lib/api';
import { Loader2, RefreshCw, BrainCircuit, Copy, Check, X } from 'lucide-react';
import { SYMBOL_NAMES } from '@/types';

function buildAiPrompt(symbol: string, data: Record<string, unknown>): string {
  const name = SYMBOL_NAMES[symbol] ?? symbol.replace('USDT', '');
  const ticker = symbol.replace('USDT', '');
  const analytics = data.analytics as Record<string, unknown> | null;
  const whale = data.whaleData as Record<string, unknown> | null;
  const derivatives = data.derivativesData as Record<string, unknown> | null;
  const price = data.priceData as Record<string, unknown> | null;
  const macro = data.macroData as Record<string, unknown> | null;
  const computed = data.computedSignal as Record<string, unknown> | null;
  const indicators = analytics?.technicalIndicators as Record<string, unknown> | null;

  const fmt = (v: unknown, decimals = 2): string => {
    if (v == null) return 'N/A';
    const n = Number(v);
    if (isNaN(n)) return String(v);
    if (Math.abs(n) >= 1e9) return `$${(n / 1e9).toFixed(2)}B`;
    if (Math.abs(n) >= 1e6) return `$${(n / 1e6).toFixed(2)}M`;
    return n.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  };
  const pct = (v: unknown): string => {
    if (v == null) return 'N/A';
    const n = Number(v);
    return isNaN(n) ? String(v) : `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;
  };
  const num = (v: unknown, d = 2): string => {
    if (v == null) return 'N/A';
    const n = Number(v);
    return isNaN(n) ? String(v) : n.toFixed(d);
  };

  const dimensions = (computed?.dimensions as Array<{ name: string; score: number; weight: number; reasons: string[] }>) ?? [];
  const dimSummary = dimensions.map(d =>
    `  - ${d.name}: ${d.score > 0 ? '+' : ''}${d.score.toFixed(0)}/100 (weight ${(d.weight * 100).toFixed(0)}%) — ${d.reasons[0] ?? 'no reason'}`
  ).join('\n');

  return `You are an expert cryptocurrency trader and technical analyst. I need your independent analysis of ${name} (${ticker}/USDT) based on real-time market data from multiple sources. Our automated scoring engine has already produced a signal, but I want your second opinion.

Review the data below carefully, then provide:

1. **Market Regime** — Is this a trending, ranging, or transitioning market? What phase are we in?
2. **Bull Case** — The strongest arguments for going long. What setups do you see?
3. **Bear Case** — The strongest arguments for going short. What risks are present?
4. **Key Levels** — Specific price levels to watch (support, resistance, liquidation clusters, psychological levels).
5. **Confluence Check** — Which data points align and reinforce each other? Which contradict?
6. **Trade Recommendation** — If you had to take one trade right now:
   - Direction (long/short/stay flat)
   - Entry price and trigger condition
   - Stop loss with reasoning
   - Take profit target(s) with reasoning
   - Position sizing suggestion (% of portfolio)
   - Expected timeframe (scalp / swing / position)
   - Confidence level (1-10) with explanation
7. **What Would Change Your Mind** — Specific conditions that would invalidate your thesis.
8. **Risk Warnings** — Any red flags, divergences, or unusual patterns in this data.

---

## PRICE DATA
- Current Price: $${fmt(price?.price)}
- 24h Change: ${pct(price?.priceChangePct24h)}
- 24h Volume: ${fmt(price?.volume24h)}
- Market Cap: ${fmt(price?.marketCap)}

## TECHNICAL INDICATORS
- RSI(14): ${num(indicators?.rsi14)}
- MACD Line: ${num(indicators?.macdLine, 4)}
- MACD Signal: ${num(indicators?.macdSignal, 4)}
- MACD Histogram: ${num(indicators?.macdHistogram, 4)}
- Bollinger Upper: $${fmt(indicators?.bollingerUpper)}
- Bollinger Middle: $${fmt(indicators?.bollingerMiddle)}
- Bollinger Lower: $${fmt(indicators?.bollingerLower)}
- SMA(20): $${fmt(indicators?.sma20)}
- SMA(50): $${fmt(indicators?.sma50)}
- SMA(200): $${fmt(indicators?.sma200)}
- EMA(12): $${fmt(indicators?.ema12)}
- EMA(26): $${fmt(indicators?.ema26)}
- ATR(14): $${fmt(indicators?.atr14)}
- Support Level: $${fmt(analytics?.supportLevel)}
- Resistance Level: $${fmt(analytics?.resistanceLevel)}
- Trend Direction: ${analytics?.trendDirection ?? 'N/A'}
- Trend Strength: ${num(analytics?.trendStrength)}
- Volume Trend: ${analytics?.volumeTrend ?? 'N/A'}

## WHALE ACTIVITY (Last 1 Hour)
- Whale Pressure: ${num(whale?.whalePressure, 0)}/100 (${whale?.pressureLabel ?? 'N/A'})
- Buy Volume: ${fmt(whale?.buyVolumeUsd1h)}
- Sell Volume: ${fmt(whale?.sellVolumeUsd1h)}
- Net Flow: ${fmt(whale?.netFlowUsd1h)}
- Trade Count: ${whale?.tradeCount1h ?? 'N/A'}
- Largest Trade: ${fmt(whale?.largestTradeUsd)}
- Activity Score: ${whale?.whaleActivityScore ?? 'N/A'}/100

## DERIVATIVES DATA
- Funding Rate: ${num(derivatives?.fundingRate, 6)} (${Number(derivatives?.fundingRate ?? 0) > 0 ? 'longs pay shorts' : Number(derivatives?.fundingRate ?? 0) < 0 ? 'shorts pay longs' : 'neutral'})
- Funding Rate Annualized: ${pct(derivatives?.fundingRateAnnualized)}
- Open Interest: ${fmt(derivatives?.openInterestUsd)}
- Long/Short Ratio: ${num(derivatives?.longShortRatio)}
- Longs: ${num(derivatives?.longPct, 1)}%
- Shorts: ${num(derivatives?.shortPct, 1)}%
- Liquidations (24h): ${fmt(derivatives?.liquidations24hUsd)}
- Derivatives Sentiment: ${derivatives?.sentiment ?? 'N/A'}

## MACRO CONTEXT
- Fear & Greed Index: ${macro?.fearGreedIndex ?? 'N/A'}/100
- BTC Dominance: ${num(macro?.btcDominance, 1)}%
- ETH Dominance: ${num(macro?.ethDominance, 1)}%
- Total Crypto Market Cap: ${fmt(macro?.totalMarketCapUsd)}
- Total Stablecoin Supply: ${fmt(macro?.totalStablecoinCap)}
- DeFi TVL: ${fmt(macro?.defiTvlUsd)}

## SENTIMENT
- News Sentiment Score: ${num(analytics?.sentimentScore)}

## OUR AUTOMATED SIGNAL (for reference — challenge it)
- Signal: ${computed?.signal ?? 'N/A'}
- Overall Score: ${num(computed?.overallScore)}/100
- Confidence: ${computed?.confidence ?? 'N/A'}%
- Dimension Breakdown:
${dimSummary || '  No dimension data'}

---

Important notes:
- Whale pressure is measured over the LAST 1 HOUR only. A reading of +100 means 100% buying, -100 means 100% selling.
- Funding rate is per 8-hour period. Positive means longs are overcrowded (contrarian bearish). Negative means shorts are overcrowded (contrarian bullish).
- Fear & Greed index: 0 = extreme fear (contrarian buy signal), 100 = extreme greed (contrarian sell signal).
- Our automated signal uses a 6-dimension weighted model: Technical 35%, Whale 20%, Derivatives 15%, Sentiment 10%, Order Book 10%, Macro 10%.
- ATR(14) represents average daily volatility — use it for stop loss sizing.

Be specific with numbers. Challenge our automated signal if you disagree. I want your honest, independent assessment.`;
}

export function AiAnalysisModal({ symbol, existingAnalysis, onClose }: { symbol: string; existingAnalysis: string | null; onClose: () => void }) {
  const [rawData, setRawData] = useState<Record<string, unknown> | null>(null);
  const [aiAnalysis, setAiAnalysis] = useState<string | null>(existingAnalysis);
  const [loading, setLoading] = useState(!existingAnalysis);
  const [requesting, setRequesting] = useState(false);
  const [copied, setCopied] = useState(false);
  const [tab, setTab] = useState<'ai' | 'prompt' | 'json'>('ai');

  useEffect(() => {
    let cancelled = false;
    api.getSignalRawData(symbol).then((data) => {
      if (!cancelled) setRawData(data);
    });
    return () => { cancelled = true; };
  }, [symbol]);

  useEffect(() => {
    if (existingAnalysis) { setLoading(false); return; }
    let cancelled = false;
    api.requestAiAnalysis(symbol).then((result) => {
      if (!cancelled && result) {
        setAiAnalysis(result.analysis);
      }
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [symbol, existingAnalysis]);

  const handleRefreshAi = async () => {
    setRequesting(true);
    const result = await api.requestAiAnalysis(symbol);
    if (result) setAiAnalysis(result.analysis);
    setRequesting(false);
  };

  const prompt = useMemo(() => rawData ? buildAiPrompt(symbol, rawData) : '', [symbol, rawData]);
  const rawJson = useMemo(() => rawData ? JSON.stringify(rawData, null, 2) : '', [rawData]);

  const handleCopy = async (text: string) => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const currentText = tab === 'ai' ? (aiAnalysis ?? '') : tab === 'prompt' ? prompt : rawJson;
  const name = SYMBOL_NAMES[symbol] ?? symbol.replace('USDT', '');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface border border-surface-border rounded-xl max-w-4xl w-full mx-4 max-h-[85vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="sticky top-0 bg-surface/95 backdrop-blur-sm border-b border-surface-border p-4 flex items-center justify-between z-10">
          <div className="flex items-center gap-2">
            <BrainCircuit className="h-5 w-5 text-accent" />
            <h2 className="text-base font-semibold text-text-primary">
              AI Analysis — {name}
            </h2>
          </div>
          <div className="flex items-center gap-2">
            <div className="flex bg-surface-light rounded-lg p-0.5 gap-0.5">
              {(['ai', 'prompt', 'json'] as const).map((t) => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={`px-2 py-1 text-[10px] rounded-md transition-colors ${
                    tab === t ? 'bg-accent/20 text-accent font-medium' : 'text-text-secondary hover:text-text-primary'
                  }`}
                >
                  {t === 'ai' ? 'Gemini' : t === 'prompt' ? 'Prompt' : 'JSON'}
                </button>
              ))}
            </div>
            {tab === 'ai' && aiAnalysis && (
              <button
                onClick={handleRefreshAi}
                disabled={requesting}
                className="flex items-center gap-1 px-2 py-1.5 text-xs rounded-lg text-text-secondary hover:text-accent transition-colors disabled:opacity-50"
              >
                <RefreshCw className={`h-3 w-3 ${requesting ? 'animate-spin' : ''}`} />
              </button>
            )}
            <button
              onClick={() => handleCopy(currentText)}
              disabled={!currentText}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-lg bg-accent/10 text-accent hover:bg-accent/20 transition-colors disabled:opacity-50"
            >
              {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
              {copied ? 'Copied!' : 'Copy'}
            </button>
            <button onClick={onClose} className="p-1 text-text-secondary hover:text-text-primary transition-colors">
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>
        <div className="overflow-y-auto flex-1 p-4">
          {tab === 'ai' && (loading || requesting) ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-6 w-6 text-accent animate-spin" />
              <span className="ml-2 text-sm text-text-secondary">
                {requesting ? 'Requesting fresh analysis from Gemini...' : 'Getting AI analysis...'}
              </span>
            </div>
          ) : tab === 'ai' && !aiAnalysis ? (
            <div className="flex flex-col items-center justify-center py-12 gap-3">
              <BrainCircuit className="h-8 w-8 text-muted" />
              <p className="text-sm text-text-secondary">No AI analysis available yet</p>
              <button
                onClick={handleRefreshAi}
                className="flex items-center gap-1.5 px-4 py-2 text-sm rounded-lg bg-accent/10 text-accent hover:bg-accent/20 transition-colors"
              >
                <BrainCircuit className="h-4 w-4" /> Request Analysis
              </button>
            </div>
          ) : tab === 'prompt' && !rawData ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-6 w-6 text-accent animate-spin" />
              <span className="ml-2 text-sm text-text-secondary">Loading data...</span>
            </div>
          ) : (
            <pre className="text-xs font-mono text-text-secondary whitespace-pre-wrap break-words leading-relaxed">
              {currentText}
            </pre>
          )}
        </div>
        {tab === 'prompt' && rawData && (
          <div className="border-t border-surface-border p-3 bg-surface-light/30 text-center">
            <p className="text-[11px] text-text-secondary">
              Click <span className="text-accent font-medium">Copy</span> and paste into ChatGPT, Claude, or any AI assistant
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
