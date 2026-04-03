import { useState, useEffect, useCallback } from 'react';
import { Bell, Trash2, Loader2, Plus } from 'lucide-react';
import { api } from '@/lib/api';
import { formatPrice, formatTimeAgo } from '@/lib/utils';
import { SYMBOL_NAMES } from '@/types';
import type { PriceAlert } from '@/types';

const SYMBOL_OPTIONS = Object.entries(SYMBOL_NAMES).map(([symbol, name]) => ({ symbol, name }));

export function AlertsManager() {
  const [alerts, setAlerts] = useState<PriceAlert[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formSymbol, setFormSymbol] = useState(SYMBOL_OPTIONS[0]?.symbol || '');
  const [formCondition, setFormCondition] = useState<'ABOVE' | 'BELOW'>('ABOVE');
  const [formTargetPrice, setFormTargetPrice] = useState('');
  const [formNote, setFormNote] = useState('');
  const [creating, setCreating] = useState(false);

  const fetchAlerts = useCallback(async () => {
    const data = await api.getAlerts();
    if (data) setAlerts(data);
    setLoading(false);
  }, []);

  useEffect(() => { fetchAlerts(); }, [fetchAlerts]);

  const handleDelete = async (id: number) => {
    setDeleting(id);
    await api.deleteAlert(id);
    await fetchAlerts();
    setDeleting(null);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    const price = parseFloat(formTargetPrice);
    if (isNaN(price) || price <= 0 || !formSymbol) return;

    setCreating(true);
    await api.createAlert(formSymbol, formCondition, price, formNote || undefined);
    setFormTargetPrice('');
    setFormNote('');
    setCreating(false);
    setShowForm(false);
    await fetchAlerts();
  };

  const activeAlerts = alerts.filter((a) => a.isActive && !a.isTriggered);
  const triggeredAlerts = alerts.filter((a) => a.isTriggered);

  if (loading) {
    return (
      <div className="glass-card p-5 flex items-center justify-center py-8">
        <Loader2 className="h-5 w-5 text-accent animate-spin" />
      </div>
    );
  }

  return (
    <div className="glass-card p-5 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
          <Bell className="h-4 w-4 text-accent" />
          Price Alerts ({activeAlerts.length} active, {triggeredAlerts.length} triggered)
        </h2>
        <button
          onClick={() => setShowForm(!showForm)}
          className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
            showForm
              ? 'bg-accent text-background'
              : 'bg-accent/10 text-accent border border-accent/30 hover:bg-accent/20'
          }`}
        >
          <Plus className="h-3.5 w-3.5" />
          New Alert
        </button>
      </div>

      {/* Create Alert Form */}
      {showForm && (
        <form onSubmit={handleCreate} className="p-4 bg-surface-light/30 border border-surface-border rounded-lg space-y-3">
          <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
            <div>
              <label className="text-xs text-text-secondary block mb-1">Symbol</label>
              <select
                value={formSymbol}
                onChange={(e) => setFormSymbol(e.target.value)}
                className="w-full px-3 py-1.5 bg-surface border border-surface-border rounded text-sm text-text-primary focus:outline-none focus:border-accent"
              >
                {SYMBOL_OPTIONS.map(({ symbol, name }) => (
                  <option key={symbol} value={symbol}>
                    {symbol.replace('USDT', '')} — {name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-secondary block mb-1">Condition</label>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setFormCondition('ABOVE')}
                  className={`flex-1 py-1.5 text-xs font-medium rounded transition-colors ${
                    formCondition === 'ABOVE'
                      ? 'bg-gain/20 text-gain border border-gain/40'
                      : 'bg-surface text-text-secondary border border-surface-border hover:border-gain/30'
                  }`}
                >
                  Above
                </button>
                <button
                  type="button"
                  onClick={() => setFormCondition('BELOW')}
                  className={`flex-1 py-1.5 text-xs font-medium rounded transition-colors ${
                    formCondition === 'BELOW'
                      ? 'bg-loss/20 text-loss border border-loss/40'
                      : 'bg-surface text-text-secondary border border-surface-border hover:border-loss/30'
                  }`}
                >
                  Below
                </button>
              </div>
            </div>
            <div>
              <label className="text-xs text-text-secondary block mb-1">Target Price ($)</label>
              <input
                type="number"
                step="any"
                min="0"
                value={formTargetPrice}
                onChange={(e) => setFormTargetPrice(e.target.value)}
                placeholder="0.00"
                className="w-full px-3 py-1.5 bg-surface border border-surface-border rounded text-sm font-mono text-text-primary focus:outline-none focus:border-accent"
                required
              />
            </div>
            <div>
              <label className="text-xs text-text-secondary block mb-1">Note (optional)</label>
              <input
                type="text"
                maxLength={255}
                value={formNote}
                onChange={(e) => setFormNote(e.target.value)}
                placeholder="e.g. Buy signal"
                className="w-full px-3 py-1.5 bg-surface border border-surface-border rounded text-sm text-text-primary placeholder-text-secondary focus:outline-none focus:border-accent"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setShowForm(false)}
              className="px-4 py-1.5 text-xs font-medium text-text-secondary hover:text-text-primary transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={creating || !formTargetPrice || parseFloat(formTargetPrice) <= 0}
              className="flex items-center gap-2 px-4 py-1.5 text-xs font-medium bg-accent text-background rounded hover:bg-accent/90 transition-colors disabled:opacity-50"
            >
              {creating ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Bell className="h-3.5 w-3.5" />}
              Create Alert
            </button>
          </div>
        </form>
      )}

      {alerts.length === 0 && !showForm ? (
        <p className="text-sm text-text-secondary py-4 text-center">
          No alerts configured. Click "New Alert" above to create one.
        </p>
      ) : alerts.length > 0 ? (
        <div className="border border-surface-border rounded-lg overflow-hidden">
          {/* Table header */}
          <div className="grid grid-cols-[1fr_80px_100px_80px_60px] px-4 py-2 bg-surface-light/50 text-xs text-text-secondary font-medium">
            <span>Symbol</span>
            <span>Condition</span>
            <span className="text-right">Target</span>
            <span className="text-center">Status</span>
            <span className="text-center">Delete</span>
          </div>

          {alerts.map((alert) => {
            const name = SYMBOL_NAMES[alert.symbol] || alert.symbol.replace('USDT', '');
            return (
              <div
                key={alert.id}
                className={`grid grid-cols-[1fr_80px_100px_80px_60px] px-4 py-3 border-t border-surface-border items-center ${
                  alert.isTriggered ? 'opacity-60' : ''
                }`}
              >
                <div>
                  <span className="text-sm font-medium text-text-primary">{name}</span>
                  <span className="text-xs text-text-secondary ml-1.5">{alert.symbol}</span>
                  {alert.note && (
                    <p className="text-[10px] text-text-secondary truncate max-w-[200px]">{alert.note}</p>
                  )}
                </div>
                <span className={`text-xs font-medium ${
                  alert.condition === 'ABOVE' ? 'text-gain' : 'text-loss'
                }`}>
                  {alert.condition}
                </span>
                <span className="text-right text-sm font-mono text-text-primary">
                  {formatPrice(alert.targetPrice)}
                </span>
                <div className="flex justify-center">
                  {alert.isTriggered ? (
                    <span className="text-[10px] px-2 py-0.5 rounded bg-accent/10 text-accent border border-accent/20">
                      {alert.triggeredAt ? formatTimeAgo(alert.triggeredAt) : 'Triggered'}
                    </span>
                  ) : (
                    <span className="text-[10px] px-2 py-0.5 rounded bg-gain/10 text-gain border border-gain/20">
                      Active
                    </span>
                  )}
                </div>
                <div className="flex justify-center">
                  <button
                    onClick={() => handleDelete(alert.id)}
                    disabled={deleting === alert.id}
                    className="p-1.5 text-text-secondary hover:text-loss hover:bg-loss/10 rounded transition-colors"
                  >
                    {deleting === alert.id
                      ? <Loader2 className="h-4 w-4 animate-spin" />
                      : <Trash2 className="h-4 w-4" />}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
