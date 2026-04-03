import { useState, useEffect, useCallback } from 'react';
import { Settings, Search, Plus, Trash2, Loader2, Check, Power, PowerOff, ArrowLeft } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { formatPrice } from '@/lib/utils';

interface CryptoAsset {
  symbol: string;
  name: string;
  rank: number;
  isActive: boolean;
}

export function CryptoConfig() {
  const [cryptos, setCryptos] = useState<CryptoAsset[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<any[]>([]);
  const [searching, setSearching] = useState(false);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const fetchCryptos = useCallback(async () => {
    const data = await api.getConfigCryptos();
    if (data) setCryptos(data);
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
          <div className="grid grid-cols-[40px_1fr_1fr_100px_80px] px-4 py-2 bg-surface-light/50 text-xs text-text-secondary font-medium">
            <span>#</span>
            <span>Symbol</span>
            <span>Name</span>
            <span className="text-center">Status</span>
            <span className="text-center">Actions</span>
          </div>
          {/* Rows */}
          {cryptos.map(crypto => (
            <div key={crypto.symbol} className={`grid grid-cols-[40px_1fr_1fr_100px_80px] px-4 py-3 border-t border-surface-border items-center ${!crypto.isActive ? 'opacity-50' : ''}`}>
              <span className="text-xs text-text-secondary">{crypto.rank}</span>
              <span className="text-sm font-medium font-mono text-text-primary">{crypto.symbol.replace('USDT', '')}</span>
              <span className="text-sm text-text-secondary">{crypto.name}</span>
              <div className="flex justify-center">
                <button
                  onClick={() => handleToggle(crypto.symbol, crypto.isActive)}
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
                  onClick={() => handleRemove(crypto.symbol)}
                  disabled={actionLoading === crypto.symbol}
                  className="p-1.5 text-text-secondary hover:text-loss hover:bg-loss/10 rounded transition-colors"
                  title="Remove and delete data"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
