import { useState } from 'react';
import { Download, FileSpreadsheet } from 'lucide-react';
import { api } from '@/lib/api';
import { SYMBOL_NAMES } from '@/types';

const SYMBOLS = Object.keys(SYMBOL_NAMES);
const INTERVALS = ['1m', '5m', '15m', '30m', '1h', '2h', '4h', '8h', '12h', '1d', '1w'];
const WHALE_PERIODS = [
  { value: '1d', label: '1 Day' },
  { value: '1w', label: '1 Week' },
  { value: '2w', label: '2 Weeks' },
  { value: '1m', label: '1 Month' },
  { value: '3m', label: '3 Months' },
];

export function DataExport() {
  const [symbol, setSymbol] = useState(SYMBOLS[0]);
  const [interval, setInterval] = useState('1h');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [whalePeriod, setWhalePeriod] = useState('1d');

  const handleCandleExport = () => {
    const url = api.getExportUrl(symbol, interval, fromDate || undefined, toDate || undefined);
    window.open(url, '_blank');
  };

  const handleWhaleExport = () => {
    window.open(api.getWhaleExportUrl(whalePeriod), '_blank');
  };

  const handleLiquidationExport = () => {
    window.open(api.getLiquidationExportUrl(), '_blank');
  };

  return (
    <div className="glass-card p-5 space-y-4">
      <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
        <FileSpreadsheet className="h-4 w-4 text-accent" />
        Export Data (CSV)
      </h2>

      {/* Candle Export */}
      <div className="space-y-3 p-4 bg-surface-light/30 rounded-lg border border-surface-border">
        <h3 className="text-xs font-medium text-text-primary">Candle / OHLCV Data</h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">Symbol</label>
            <select
              value={symbol}
              onChange={e => setSymbol(e.target.value)}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs text-text-primary focus:outline-none focus:border-accent"
            >
              {SYMBOLS.map(s => (
                <option key={s} value={s}>{s.replace('USDT', '')} - {SYMBOL_NAMES[s]}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">Interval</label>
            <select
              value={interval}
              onChange={e => setInterval(e.target.value)}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs text-text-primary focus:outline-none focus:border-accent"
            >
              {INTERVALS.map(i => (
                <option key={i} value={i}>{i}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">From</label>
            <input
              type="date"
              value={fromDate}
              onChange={e => setFromDate(e.target.value)}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs text-text-primary focus:outline-none focus:border-accent"
            />
          </div>
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">To</label>
            <input
              type="date"
              value={toDate}
              onChange={e => setToDate(e.target.value)}
              className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-xs text-text-primary focus:outline-none focus:border-accent"
            />
          </div>
        </div>
        <button
          onClick={handleCandleExport}
          className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium bg-accent/10 text-accent border border-accent/30 rounded hover:bg-accent/20 transition-colors"
        >
          <Download className="h-3.5 w-3.5" />
          Download OHLCV CSV
        </button>
      </div>

      {/* Whale Export */}
      <div className="space-y-3 p-4 bg-surface-light/30 rounded-lg border border-surface-border">
        <h3 className="text-xs font-medium text-text-primary">Whale Transactions</h3>
        <div className="flex items-end gap-3">
          <div>
            <label className="text-[10px] text-text-secondary block mb-1">Period</label>
            <select
              value={whalePeriod}
              onChange={e => setWhalePeriod(e.target.value)}
              className="px-2 py-1.5 bg-surface border border-surface-border rounded text-xs text-text-primary focus:outline-none focus:border-accent"
            >
              {WHALE_PERIODS.map(p => (
                <option key={p.value} value={p.value}>{p.label}</option>
              ))}
            </select>
          </div>
          <button
            onClick={handleWhaleExport}
            className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium bg-accent/10 text-accent border border-accent/30 rounded hover:bg-accent/20 transition-colors"
          >
            <Download className="h-3.5 w-3.5" />
            Download Whale CSV
          </button>
        </div>
      </div>

      {/* Liquidation Export */}
      <div className="space-y-3 p-4 bg-surface-light/30 rounded-lg border border-surface-border">
        <h3 className="text-xs font-medium text-text-primary">Liquidations (Last 7 days)</h3>
        <button
          onClick={handleLiquidationExport}
          className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium bg-accent/10 text-accent border border-accent/30 rounded hover:bg-accent/20 transition-colors"
        >
          <Download className="h-3.5 w-3.5" />
          Download Liquidations CSV
        </button>
      </div>
    </div>
  );
}
