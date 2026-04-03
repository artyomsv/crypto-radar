import { useState, useEffect, useCallback } from 'react';
import { Bell, Trash2, Loader2 } from 'lucide-react';
import { api } from '@/lib/api';
import { formatPrice, formatTimeAgo } from '@/lib/utils';
import { SYMBOL_NAMES } from '@/types';
import type { PriceAlert } from '@/types';

export function AlertsManager() {
  const [alerts, setAlerts] = useState<PriceAlert[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState<number | null>(null);

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
      <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
        <Bell className="h-4 w-4 text-accent" />
        Price Alerts ({activeAlerts.length} active, {triggeredAlerts.length} triggered)
      </h2>

      {alerts.length === 0 ? (
        <p className="text-sm text-text-secondary py-4 text-center">
          No alerts configured. Create alerts from any crypto detail page.
        </p>
      ) : (
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
      )}
    </div>
  );
}
