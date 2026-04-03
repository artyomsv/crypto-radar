import { useState } from 'react';
import { Bell, Loader2, X } from 'lucide-react';
import { api } from '@/lib/api';

interface AddAlertFormProps {
  symbol: string;
  currentPrice: number;
  onClose: () => void;
  onCreated: () => void;
}

export function AddAlertForm({ symbol, currentPrice, onClose, onCreated }: AddAlertFormProps) {
  const [condition, setCondition] = useState<'ABOVE' | 'BELOW'>('ABOVE');
  const [targetPrice, setTargetPrice] = useState(currentPrice.toFixed(2));
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const price = parseFloat(targetPrice);
    if (isNaN(price) || price <= 0) return;

    setSaving(true);
    await api.createAlert(symbol, condition, price, note || undefined);
    setSaving(false);
    onCreated();
    onClose();
  };

  return (
    <div className="absolute top-full right-0 mt-2 z-50 w-72">
      <form
        onSubmit={handleSubmit}
        className="glass-card border border-accent/30 p-4 shadow-lg space-y-3"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Bell className="h-4 w-4 text-accent" />
            <span className="text-sm font-semibold text-text-primary">Add Alert</span>
          </div>
          <button type="button" onClick={onClose} className="text-text-secondary hover:text-text-primary">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="text-xs text-text-secondary">
          {symbol} — Current: <span className="font-mono text-accent">${currentPrice.toLocaleString()}</span>
        </div>

        <div>
          <label className="text-xs text-text-secondary block mb-1">Condition</label>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setCondition('ABOVE')}
              className={`flex-1 py-1.5 text-xs font-medium rounded transition-colors ${
                condition === 'ABOVE'
                  ? 'bg-gain/20 text-gain border border-gain/40'
                  : 'bg-surface-light text-text-secondary border border-surface-border hover:border-gain/30'
              }`}
            >
              Above
            </button>
            <button
              type="button"
              onClick={() => setCondition('BELOW')}
              className={`flex-1 py-1.5 text-xs font-medium rounded transition-colors ${
                condition === 'BELOW'
                  ? 'bg-loss/20 text-loss border border-loss/40'
                  : 'bg-surface-light text-text-secondary border border-surface-border hover:border-loss/30'
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
            value={targetPrice}
            onChange={(e) => setTargetPrice(e.target.value)}
            className="w-full px-3 py-1.5 bg-surface-light border border-surface-border rounded text-sm font-mono text-text-primary focus:outline-none focus:border-accent"
            required
          />
        </div>

        <div>
          <label className="text-xs text-text-secondary block mb-1">Note (optional)</label>
          <input
            type="text"
            maxLength={255}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. Buy more if it dips"
            className="w-full px-3 py-1.5 bg-surface-light border border-surface-border rounded text-sm text-text-primary placeholder-text-secondary focus:outline-none focus:border-accent"
          />
        </div>

        <button
          type="submit"
          disabled={saving || !targetPrice || parseFloat(targetPrice) <= 0}
          className="w-full flex items-center justify-center gap-2 py-2 text-sm font-medium bg-accent text-background rounded hover:bg-accent/90 transition-colors disabled:opacity-50"
        >
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Bell className="h-4 w-4" />}
          Create Alert
        </button>
      </form>
    </div>
  );
}
