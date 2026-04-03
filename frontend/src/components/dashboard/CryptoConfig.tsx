import { useState, useEffect, useCallback } from 'react';
import { Settings, Search, Plus, Trash2, Loader2, Power, PowerOff, ArrowLeft, ChevronDown, ChevronRight, Database, Clock, Save } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { formatPrice, formatLargeNumber } from '@/lib/utils';
import { AlertsManager } from './AlertsManager';
import { DataExport } from './DataExport';

interface CandleStat {
  interval: string;
  count: number;
  oldest: string;
  newest: string;
}

interface CryptoAsset {
  symbol: string;
  name: string;
  rank: number;
  isActive: boolean;
  totalCandles?: number;
  priceSnapshots?: number;
  whaleTrades?: number;
  oldestCandle?: string;
  newestCandle?: string;
  candleStats?: CandleStat[];
}

interface BackfillConfig {
  interval: string;
  depthDays: number;
  description: string;
}

export function CryptoConfig() {
  const [cryptos, setCryptos] = useState<CryptoAsset[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<any[]>([]);
  const [searching, setSearching] = useState(false);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [backfillConfig, setBackfillConfig] = useState<BackfillConfig[]>([]);
  const [editedDepths, setEditedDepths] = useState<Record<string, number>>({});
  const [savingInterval, setSavingInterval] = useState<string | null>(null);

  const fetchCryptos = useCallback(async () => {
    const [cryptoData, bfConfig] = await Promise.all([
      api.getConfigCryptos(),
      api.getBackfillConfig(),
    ]);
    if (cryptoData) setCryptos(cryptoData);
    if (bfConfig) setBackfillConfig(bfConfig);
    setLoading(false);
  }, []);

  useEffect(() => { fetchCryptos(); }, [fetchCryptos]);

  // Debounced search
  useEffect(() => {
    if (searchQuery.length < 2) { setSearchResults([]); return; }
    const timer = setTimeout(async () => {
      setSearching(true);
      const results = await api.searchSymbols(searchQuery);
      // Filter out already tracked symbols
      const tracked = new Set(cryptos.map(c => c.symbol));
      setSearchResults((results || []).filter(r => !tracked.has(r.symbol)));
      setSearching(false);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchQuery, cryptos]);

  const handleAdd = async (symbol: string) => {
    setActionLoading(symbol);
    const name = symbol.replace('USDT', '');
    await api.addCrypto(symbol, name);
    setSearchQuery('');
    setSearchResults([]);
    await fetchCryptos();
    setActionLoading(null);
  };

  const handleToggle = async (symbol: string, currentActive: boolean) => {
    setActionLoading(symbol);
    await api.toggleCrypto(symbol, !currentActive);
    await fetchCryptos();
    setActionLoading(null);
  };

  const handleRemove = async (symbol: string) => {
    if (!confirm(`Remove ${symbol}? This will delete all historical data for this symbol.`)) return;
    setActionLoading(symbol);
    await api.removeCrypto(symbol, true);
    await fetchCryptos();
    setActionLoading(null);
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <Loader2 className="h-8 w-8 text-accent animate-spin" />
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Link to="/" className="text-text-secondary hover:text-accent transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </Link>
        <div className="flex items-center gap-2">
          <Settings className="h-6 w-6 text-accent" />
          <div>
            <h1 className="text-xl font-bold text-text-primary">Crypto Configuration</h1>
            <p className="text-sm text-text-secondary">Add, remove, or toggle monitoring for cryptocurrencies</p>
          </div>
        </div>
      </div>

      {/* Price Alerts */}
      <AlertsManager />

      {/* Data Export */}
      <DataExport />

      {/* Search to add new */}
      <div className="glass-card p-5 space-y-3">
        <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
          <Plus className="h-4 w-4 text-accent" />
          Add Cryptocurrency
        </h2>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-text-secondary" />
          <input
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Search by symbol (e.g. BTC, MATIC, PEPE...)"
            className="w-full pl-10 pr-4 py-2.5 bg-surface-light border border-surface-border rounded-lg text-sm text-text-primary placeholder-text-secondary focus:outline-none focus:border-accent"
          />
          {searching && <Loader2 className="absolute right-3 top-1/2 -translate-y-1/2 h-4 w-4 text-accent animate-spin" />}
        </div>

        {searchResults.length > 0 && (
          <div className="border border-surface-border rounded-lg overflow-hidden">
            {searchResults.slice(0, 10).map(result => (
              <div key={result.symbol} className="flex items-center justify-between px-4 py-2.5 border-b border-surface-border last:border-b-0 hover:bg-surface-light/50">
                <div>
                  <span className="text-sm font-medium text-text-primary">{result.symbol.replace('USDT', '')}</span>
                  <span className="text-xs text-text-secondary ml-2">{result.symbol}</span>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-xs text-text-secondary font-mono">{formatPrice(result.price)}</span>
                  <button
                    onClick={() => handleAdd(result.symbol)}
                    disabled={actionLoading === result.symbol}
                    className="flex items-center gap-1 px-3 py-1 text-xs font-medium bg-accent/10 text-accent border border-accent/30 rounded hover:bg-accent/20 transition-colors disabled:opacity-50"
                  >
                    {actionLoading === result.symbol ? <Loader2 className="h-3 w-3 animate-spin" /> : <Plus className="h-3 w-3" />}
                    Add
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Current tracked cryptos */}
      <div className="glass-card p-5 space-y-3">
        <h2 className="text-sm font-semibold text-text-primary">
          Tracked Cryptocurrencies ({cryptos.filter(c => c.isActive).length} active / {cryptos.length} total)
        </h2>
        <div className="border border-surface-border rounded-lg overflow-hidden">
          {/* Table header */}
          <div className="grid grid-cols-[24px_40px_80px_1fr_120px_100px_80px] px-4 py-2 bg-surface-light/50 text-xs text-text-secondary font-medium">
            <span></span>
            <span>#</span>
            <span>Symbol</span>
            <span>Name</span>
            <span className="text-right">Data Stored</span>
            <span className="text-center">Status</span>
            <span className="text-center">Actions</span>
          </div>
          {/* Rows */}
          {cryptos.map(crypto => (
            <div key={crypto.symbol}>
              <div
                className={`grid grid-cols-[24px_40px_80px_1fr_120px_100px_80px] px-4 py-3 border-t border-surface-border items-center cursor-pointer hover:bg-surface-light/30 ${!crypto.isActive ? 'opacity-50' : ''}`}
                onClick={() => setExpanded(expanded === crypto.symbol ? null : crypto.symbol)}
              >
                <span className="text-text-secondary">
                  {expanded === crypto.symbol ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                </span>
                <span className="text-xs text-text-secondary">{crypto.rank}</span>
                <span className="text-sm font-medium font-mono text-text-primary">{crypto.symbol.replace('USDT', '')}</span>
                <span className="text-sm text-text-secondary">{crypto.name}</span>
                <span className="text-right text-xs font-mono text-accent">
                  {(crypto.totalCandles || 0).toLocaleString()} candles
                </span>
                <div className="flex justify-center">
                  <button
                    onClick={(e) => { e.stopPropagation(); handleToggle(crypto.symbol, crypto.isActive); }}
                    disabled={actionLoading === crypto.symbol}
                    className={`flex items-center gap-1 px-2 py-1 text-xs rounded transition-colors ${
                      crypto.isActive
                        ? 'bg-gain/10 text-gain border border-gain/30'
                        : 'bg-loss/10 text-loss border border-loss/30'
                    }`}
                  >
                    {actionLoading === crypto.symbol ? (
                      <Loader2 className="h-3 w-3 animate-spin" />
                    ) : crypto.isActive ? (
                      <><Power className="h-3 w-3" /> Active</>
                    ) : (
                      <><PowerOff className="h-3 w-3" /> Paused</>
                    )}
                  </button>
                </div>
                <div className="flex justify-center">
                  <button
                    onClick={(e) => { e.stopPropagation(); handleRemove(crypto.symbol); }}
                    disabled={actionLoading === crypto.symbol}
                    className="p-1.5 text-text-secondary hover:text-loss hover:bg-loss/10 rounded transition-colors"
                    title="Remove and delete data"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>

              {/* Expanded stats */}
              {expanded === crypto.symbol && (
                <div className="px-4 py-3 border-t border-surface-border/50 bg-surface-light/20">
                  <div className="grid grid-cols-3 gap-4 mb-3">
                    <div className="flex items-center gap-2">
                      <Database className="h-3.5 w-3.5 text-accent" />
                      <span className="text-xs text-text-secondary">Total Candles:</span>
                      <span className="text-xs font-mono text-text-primary font-medium">{(crypto.totalCandles || 0).toLocaleString()}</span>
                    </div>
                    <div>
                      <span className="text-xs text-text-secondary">Whale Trades:</span>
                      <span className="text-xs font-mono text-text-primary font-medium ml-1">{(crypto.whaleTrades || 0).toLocaleString()}</span>
                    </div>
                    <div>
                      <span className="text-xs text-text-secondary">Price Snapshots:</span>
                      <span className="text-xs font-mono text-text-primary font-medium ml-1">{(crypto.priceSnapshots || 0).toLocaleString()}</span>
                    </div>
                  </div>
                  {crypto.oldestCandle && (
                    <div className="text-[10px] text-text-secondary mb-3">
                      History: {new Date(crypto.oldestCandle).toLocaleDateString()} — {crypto.newestCandle ? new Date(crypto.newestCandle).toLocaleDateString() : 'now'}
                    </div>
                  )}
                  {crypto.candleStats && crypto.candleStats.length > 0 && (
                    <div className="grid grid-cols-4 sm:grid-cols-6 lg:grid-cols-11 gap-2">
                      {crypto.candleStats.map((stat: CandleStat) => (
                        <div key={stat.interval} className="text-center px-2 py-1.5 bg-surface/50 rounded border border-surface-border">
                          <p className="text-[10px] text-accent font-medium">{stat.interval}</p>
                          <p className="text-xs font-mono text-text-primary">{stat.count.toLocaleString()}</p>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Backfill Depth Configuration */}
      <div className="glass-card p-5 space-y-3">
        <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
          <Clock className="h-4 w-4 text-accent" />
          Historical Data Depth (days per interval)
        </h2>
        <p className="text-xs text-text-secondary">Configure how far back to fetch candle data from Binance. Changes apply on next backfill cycle.</p>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-3">
          {backfillConfig.map(cfg => {
            const edited = editedDepths[cfg.interval];
            const currentValue = edited !== undefined ? edited : cfg.depthDays;
            const isModified = edited !== undefined && edited !== cfg.depthDays;

            return (
              <div key={cfg.interval} className={`p-3 rounded-lg border ${isModified ? 'border-accent/50 bg-accent/5' : 'border-surface-border bg-surface-light/30'}`}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs font-medium text-accent">{cfg.interval}</span>
                  {isModified && (
                    <button
                      onClick={async () => {
                        setSavingInterval(cfg.interval);
                        await api.updateBackfillDepth(cfg.interval, currentValue);
                        setEditedDepths(prev => { const n = {...prev}; delete n[cfg.interval]; return n; });
                        const updated = await api.getBackfillConfig();
                        if (updated) setBackfillConfig(updated);
                        setSavingInterval(null);
                      }}
                      disabled={savingInterval === cfg.interval}
                      className="text-[10px] flex items-center gap-0.5 text-accent hover:text-gain"
                    >
                      {savingInterval === cfg.interval ? <Loader2 className="h-3 w-3 animate-spin" /> : <Save className="h-3 w-3" />}
                      Save
                    </button>
                  )}
                </div>
                <input
                  type="number"
                  min={1}
                  max={5000}
                  value={currentValue}
                  onChange={e => setEditedDepths(prev => ({ ...prev, [cfg.interval]: parseInt(e.target.value) || 0 }))}
                  className="w-full px-2 py-1.5 bg-surface border border-surface-border rounded text-sm font-mono text-text-primary focus:outline-none focus:border-accent"
                />
                <p className="text-[10px] text-text-secondary mt-1">{cfg.description}</p>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
