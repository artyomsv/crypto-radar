import { useState, useEffect, useRef, useCallback } from 'react';
import { Loader2, Wallet, Plus, X, TrendingUp, TrendingDown, Trash2, CheckCircle } from 'lucide-react';
import { api } from '@/lib/api';
import { SYMBOL_NAMES, SYMBOL_ICONS } from '@/types';
import type { PortfolioPosition } from '@/types';
import { formatPrice, formatPercent, formatLargeNumber } from '@/lib/utils';
import { useWebSocket } from '@/hooks/useWebSocket';

const ALL_SYMBOLS = Object.keys(SYMBOL_NAMES);

export function PortfolioTracker() {
  const [positions, setPositions] = useState<PortfolioPosition[]>([]);
  const [loading, setLoading] = useState(true);
  const [livePrices, setLivePrices] = useState<Record<string, number>>({});
  const [showAddForm, setShowAddForm] = useState(false);
  const [closingId, setClosingId] = useState<number | null>(null);

  const fetchPositions = useCallback(async () => {
    const data = await api.getPortfolio();
    if (data) setPositions(data);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchPositions();
  }, [fetchPositions]);

  useWebSocket({
    onPrices: (prices: any[]) => {
      if (!prices || !Array.isArray(prices)) return;
      setLivePrices(prev => {
        const next = { ...prev };
        for (const p of prices) {
          if (p.symbol && p.price) next[p.symbol] = p.price;
        }
        return next;
      });
    },
  });

  const handleAddPosition = async (pos: { symbol: string; entryPrice: number; quantity: number; side: string; note?: string }) => {
    const result = await api.addPosition(pos);
    if (!result.error) {
      setShowAddForm(false);
      fetchPositions();
    }
  };

  const handleClosePosition = async (id: number) => {
    const position = positions.find(p => p.id === id);
    if (!position) return;
    const currentPrice = livePrices[position.symbol];
    if (!currentPrice) return;

    const result = await api.closePosition(id, currentPrice);
    if (!result.error) {
      setClosingId(null);
      fetchPositions();
    }
  };

  const handleDeletePosition = async (id: number) => {
    const result = await api.deletePosition(id);
    if (!result.error) {
      fetchPositions();
    }
  };

  const openPositions = positions.filter(p => p.isOpen);
  const closedPositions = positions.filter(p => !p.isOpen);

  // Portfolio summary from open positions
  const portfolioSummary = calculatePortfolioSummary(openPositions, livePrices);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <Loader2 className="h-8 w-8 text-accent animate-spin" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Wallet className="h-5 w-5 text-accent" />
          <h1 className="text-xl font-bold text-text-primary">Portfolio Tracker</h1>
        </div>
        <button
          onClick={() => setShowAddForm(!showAddForm)}
          className={`flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg transition-all ${
            showAddForm
              ? 'bg-surface-light text-text-secondary border border-surface-border'
              : 'bg-accent text-background hover:bg-accent/90'
          }`}
        >
          {showAddForm ? <X className="h-4 w-4" /> : <Plus className="h-4 w-4" />}
          {showAddForm ? 'Cancel' : 'Add Position'}
        </button>
      </div>

      {/* Add Position Form */}
      {showAddForm && (
        <AddPositionForm onSubmit={handleAddPosition} onCancel={() => setShowAddForm(false)} />
      )}

      {/* Portfolio Summary */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <SummaryCard
          label="Total Value"
          value={formatLargeNumber(portfolioSummary.totalValue)}
          subtext={`${openPositions.length} open position${openPositions.length !== 1 ? 's' : ''}`}
        />
        <SummaryCard
          label="Unrealized P&L"
          value={`${portfolioSummary.totalPnl >= 0 ? '+' : ''}$${Math.abs(portfolioSummary.totalPnl).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`}
          valueColor={portfolioSummary.totalPnl >= 0 ? 'text-gain' : 'text-loss'}
          subtext={formatPercent(portfolioSummary.totalPnlPct)}
          subtextColor={portfolioSummary.totalPnlPct >= 0 ? 'text-gain' : 'text-loss'}
        />
        <SummaryCard
          label="Total Cost Basis"
          value={formatLargeNumber(portfolioSummary.totalCost)}
          subtext="Entry cost"
        />
      </div>

      {/* Open Positions */}
      <div className="glass-card overflow-hidden">
        <div className="p-4 border-b border-surface-border">
          <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
            <TrendingUp className="h-4 w-4 text-accent" />
            Open Positions
          </h2>
        </div>
        {openPositions.length === 0 ? (
          <div className="p-8 text-center text-text-secondary text-sm">
            No open positions. Click "Add Position" to get started.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-surface-border text-text-secondary text-xs">
                  <th className="text-left px-4 py-2.5 font-medium">Symbol</th>
                  <th className="text-left px-4 py-2.5 font-medium">Side</th>
                  <th className="text-right px-4 py-2.5 font-medium">Entry</th>
                  <th className="text-right px-4 py-2.5 font-medium">Current</th>
                  <th className="text-right px-4 py-2.5 font-medium">Qty</th>
                  <th className="text-right px-4 py-2.5 font-medium">Value</th>
                  <th className="text-right px-4 py-2.5 font-medium">P&L ($)</th>
                  <th className="text-right px-4 py-2.5 font-medium">P&L (%)</th>
                  <th className="text-right px-4 py-2.5 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {openPositions.map(pos => {
                  const currentPrice = livePrices[pos.symbol] ?? pos.entryPrice;
                  const pnl = calculatePnl(pos, currentPrice);
                  return (
                    <tr key={pos.id} className="border-b border-surface-border/50 hover:bg-surface-light/30 transition-colors">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <span className="text-accent font-mono">{SYMBOL_ICONS[pos.symbol] || '?'}</span>
                          <div>
                            <p className="font-medium text-text-primary">{SYMBOL_NAMES[pos.symbol] || pos.symbol}</p>
                            {pos.note && <p className="text-xs text-text-secondary truncate max-w-[120px]">{pos.note}</p>}
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${
                          pos.side === 'LONG' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'
                        }`}>
                          {pos.side}
                        </span>
                      </td>
                      <td className="text-right px-4 py-3 font-mono text-text-primary">{formatPrice(pos.entryPrice)}</td>
                      <td className="text-right px-4 py-3 font-mono text-text-primary">{formatPrice(currentPrice)}</td>
                      <td className="text-right px-4 py-3 font-mono text-text-secondary">{pos.quantity}</td>
                      <td className="text-right px-4 py-3 font-mono text-text-primary">
                        {formatLargeNumber(currentPrice * pos.quantity)}
                      </td>
                      <td className={`text-right px-4 py-3 font-mono font-medium ${pnl.value >= 0 ? 'text-gain' : 'text-loss'}`}>
                        {pnl.value >= 0 ? '+' : ''}{pnl.value.toLocaleString('en-US', { style: 'currency', currency: 'USD' })}
                      </td>
                      <td className={`text-right px-4 py-3 font-mono font-medium ${pnl.pct >= 0 ? 'text-gain' : 'text-loss'}`}>
                        {formatPercent(pnl.pct)}
                      </td>
                      <td className="text-right px-4 py-3">
                        <div className="flex items-center justify-end gap-1">
                          {closingId === pos.id ? (
                            <div className="flex items-center gap-1">
                              <button
                                onClick={() => handleClosePosition(pos.id)}
                                className="p-1 rounded text-gain hover:bg-gain/10 transition-colors"
                                title="Confirm close"
                              >
                                <CheckCircle className="h-4 w-4" />
                              </button>
                              <button
                                onClick={() => setClosingId(null)}
                                className="p-1 rounded text-text-secondary hover:bg-surface-light transition-colors"
                                title="Cancel"
                              >
                                <X className="h-4 w-4" />
                              </button>
                            </div>
                          ) : (
                            <>
                              <button
                                onClick={() => setClosingId(pos.id)}
                                className="p-1 rounded text-yellow-400 hover:bg-yellow-400/10 transition-colors"
                                title="Close position at current price"
                              >
                                <CheckCircle className="h-4 w-4" />
                              </button>
                              <button
                                onClick={() => handleDeletePosition(pos.id)}
                                className="p-1 rounded text-loss hover:bg-loss/10 transition-colors"
                                title="Delete position"
                              >
                                <Trash2 className="h-4 w-4" />
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Closed Positions */}
      {closedPositions.length > 0 && (
        <div className="glass-card overflow-hidden">
          <div className="p-4 border-b border-surface-border">
            <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
              <TrendingDown className="h-4 w-4 text-text-secondary" />
              Closed Positions
              <span className="text-xs text-text-secondary font-normal">({closedPositions.length})</span>
            </h2>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-surface-border text-text-secondary text-xs">
                  <th className="text-left px-4 py-2.5 font-medium">Symbol</th>
                  <th className="text-left px-4 py-2.5 font-medium">Side</th>
                  <th className="text-right px-4 py-2.5 font-medium">Entry</th>
                  <th className="text-right px-4 py-2.5 font-medium">Close</th>
                  <th className="text-right px-4 py-2.5 font-medium">Qty</th>
                  <th className="text-right px-4 py-2.5 font-medium">P&L ($)</th>
                  <th className="text-right px-4 py-2.5 font-medium">P&L (%)</th>
                  <th className="text-right px-4 py-2.5 font-medium">Closed</th>
                  <th className="text-right px-4 py-2.5 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {closedPositions.map(pos => {
                  const closePrice = pos.closePrice ?? pos.entryPrice;
                  const pnl = calculatePnl(pos, closePrice);
                  return (
                    <tr key={pos.id} className="border-b border-surface-border/50 hover:bg-surface-light/30 transition-colors opacity-70">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <span className="text-accent font-mono">{SYMBOL_ICONS[pos.symbol] || '?'}</span>
                          <div>
                            <p className="font-medium text-text-primary">{SYMBOL_NAMES[pos.symbol] || pos.symbol}</p>
                            {pos.note && <p className="text-xs text-text-secondary truncate max-w-[120px]">{pos.note}</p>}
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${
                          pos.side === 'LONG' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'
                        }`}>
                          {pos.side}
                        </span>
                      </td>
                      <td className="text-right px-4 py-3 font-mono text-text-primary">{formatPrice(pos.entryPrice)}</td>
                      <td className="text-right px-4 py-3 font-mono text-text-primary">{formatPrice(closePrice)}</td>
                      <td className="text-right px-4 py-3 font-mono text-text-secondary">{pos.quantity}</td>
                      <td className={`text-right px-4 py-3 font-mono font-medium ${pnl.value >= 0 ? 'text-gain' : 'text-loss'}`}>
                        {pnl.value >= 0 ? '+' : ''}{pnl.value.toLocaleString('en-US', { style: 'currency', currency: 'USD' })}
                      </td>
                      <td className={`text-right px-4 py-3 font-mono font-medium ${pnl.pct >= 0 ? 'text-gain' : 'text-loss'}`}>
                        {formatPercent(pnl.pct)}
                      </td>
                      <td className="text-right px-4 py-3 text-text-secondary text-xs">
                        {pos.closedAt ? new Date(pos.closedAt).toLocaleDateString() : '--'}
                      </td>
                      <td className="text-right px-4 py-3">
                        <button
                          onClick={() => handleDeletePosition(pos.id)}
                          className="p-1 rounded text-loss/60 hover:text-loss hover:bg-loss/10 transition-colors"
                          title="Delete position"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

// --- Summary Card ---

interface SummaryCardProps {
  label: string;
  value: string;
  valueColor?: string;
  subtext: string;
  subtextColor?: string;
}

function SummaryCard({ label, value, valueColor, subtext, subtextColor }: SummaryCardProps) {
  return (
    <div className="glass-card p-4">
      <p className="text-xs text-text-secondary mb-1">{label}</p>
      <p className={`text-xl font-bold font-mono ${valueColor || 'text-text-primary'}`}>{value}</p>
      <p className={`text-xs mt-1 ${subtextColor || 'text-text-secondary'}`}>{subtext}</p>
    </div>
  );
}

// --- Add Position Form ---

interface AddPositionFormProps {
  onSubmit: (pos: { symbol: string; entryPrice: number; quantity: number; side: string; note?: string }) => void;
  onCancel: () => void;
}

function AddPositionForm({ onSubmit, onCancel }: AddPositionFormProps) {
  const [symbol, setSymbol] = useState(ALL_SYMBOLS[0]);
  const [entryPrice, setEntryPrice] = useState('');
  const [quantity, setQuantity] = useState('');
  const [side, setSide] = useState('LONG');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const ep = parseFloat(entryPrice);
    const qty = parseFloat(quantity);
    if (isNaN(ep) || ep <= 0 || isNaN(qty) || qty <= 0) return;

    setSubmitting(true);
    await onSubmit({ symbol, entryPrice: ep, quantity: qty, side, note: note || undefined });
    setSubmitting(false);
  };

  return (
    <form onSubmit={handleSubmit} className="glass-card p-4">
      <div className="grid grid-cols-1 md:grid-cols-6 gap-3 items-end">
        {/* Symbol */}
        <div>
          <label className="text-xs text-text-secondary block mb-1">Symbol</label>
          <select
            value={symbol}
            onChange={e => setSymbol(e.target.value)}
            className="w-full bg-surface-light border border-surface-border rounded px-2.5 py-1.5 text-sm text-text-primary focus:outline-none focus:border-accent"
          >
            {ALL_SYMBOLS.map(sym => (
              <option key={sym} value={sym}>{SYMBOL_NAMES[sym]} ({sym.replace('USDT', '')})</option>
            ))}
          </select>
        </div>

        {/* Entry Price */}
        <div>
          <label className="text-xs text-text-secondary block mb-1">Entry Price</label>
          <input
            type="number"
            step="any"
            value={entryPrice}
            onChange={e => setEntryPrice(e.target.value)}
            placeholder="0.00"
            required
            className="w-full bg-surface-light border border-surface-border rounded px-2.5 py-1.5 text-sm text-text-primary font-mono focus:outline-none focus:border-accent"
          />
        </div>

        {/* Quantity */}
        <div>
          <label className="text-xs text-text-secondary block mb-1">Quantity</label>
          <input
            type="number"
            step="any"
            value={quantity}
            onChange={e => setQuantity(e.target.value)}
            placeholder="0.00"
            required
            className="w-full bg-surface-light border border-surface-border rounded px-2.5 py-1.5 text-sm text-text-primary font-mono focus:outline-none focus:border-accent"
          />
        </div>

        {/* Side */}
        <div>
          <label className="text-xs text-text-secondary block mb-1">Side</label>
          <div className="flex gap-1">
            <button
              type="button"
              onClick={() => setSide('LONG')}
              className={`flex-1 px-2.5 py-1.5 text-sm font-medium rounded transition-all ${
                side === 'LONG'
                  ? 'bg-gain text-background'
                  : 'bg-surface-light text-text-secondary hover:text-text-primary border border-surface-border'
              }`}
            >
              Long
            </button>
            <button
              type="button"
              onClick={() => setSide('SHORT')}
              className={`flex-1 px-2.5 py-1.5 text-sm font-medium rounded transition-all ${
                side === 'SHORT'
                  ? 'bg-loss text-background'
                  : 'bg-surface-light text-text-secondary hover:text-text-primary border border-surface-border'
              }`}
            >
              Short
            </button>
          </div>
        </div>

        {/* Note */}
        <div>
          <label className="text-xs text-text-secondary block mb-1">Note</label>
          <input
            type="text"
            value={note}
            onChange={e => setNote(e.target.value)}
            placeholder="Optional"
            maxLength={255}
            className="w-full bg-surface-light border border-surface-border rounded px-2.5 py-1.5 text-sm text-text-primary focus:outline-none focus:border-accent"
          />
        </div>

        {/* Submit */}
        <div>
          <button
            type="submit"
            disabled={submitting || !entryPrice || !quantity}
            className="w-full px-3 py-1.5 text-sm font-medium rounded-lg bg-accent text-background hover:bg-accent/90 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center justify-center gap-1.5"
          >
            {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
            Add
          </button>
        </div>
      </div>
    </form>
  );
}

// --- Helpers ---

function calculatePnl(position: PortfolioPosition, currentPrice: number) {
  const { entryPrice, quantity, side } = position;
  const direction = side === 'LONG' ? 1 : -1;
  const pnlValue = (currentPrice - entryPrice) * quantity * direction;
  const pnlPct = entryPrice > 0 ? ((currentPrice - entryPrice) / entryPrice) * 100 * direction : 0;
  return { value: pnlValue, pct: pnlPct };
}

function calculatePortfolioSummary(
  openPositions: PortfolioPosition[],
  livePrices: Record<string, number>
) {
  let totalValue = 0;
  let totalCost = 0;
  let totalPnl = 0;

  for (const pos of openPositions) {
    const currentPrice = livePrices[pos.symbol] ?? pos.entryPrice;
    const posValue = currentPrice * pos.quantity;
    const posCost = pos.entryPrice * pos.quantity;
    const direction = pos.side === 'LONG' ? 1 : -1;
    const posPnl = (currentPrice - pos.entryPrice) * pos.quantity * direction;

    totalValue += posValue;
    totalCost += posCost;
    totalPnl += posPnl;
  }

  const totalPnlPct = totalCost > 0 ? (totalPnl / totalCost) * 100 : 0;
  return { totalValue, totalCost, totalPnl, totalPnlPct };
}
