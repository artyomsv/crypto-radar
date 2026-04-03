import { useState, useEffect, useCallback } from 'react';
import { Bell, X, TrendingUp, TrendingDown } from 'lucide-react';
import { formatPrice } from '@/lib/utils';
import { SYMBOL_NAMES } from '@/types';
import type { TriggeredAlert } from '@/types';

interface AlertToastProps {
  alerts: TriggeredAlert[];
  onDismiss: (id: number) => void;
}

const ALERT_SOUND_FREQUENCY = 880;
const ALERT_SOUND_DURATION = 150;
const AUTO_DISMISS_MS = 10000;

function playAlertSound() {
  try {
    const ctx = new AudioContext();
    const oscillator = ctx.createOscillator();
    const gain = ctx.createGain();
    oscillator.connect(gain);
    gain.connect(ctx.destination);
    oscillator.frequency.value = ALERT_SOUND_FREQUENCY;
    oscillator.type = 'sine';
    gain.gain.value = 0.3;
    oscillator.start();
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + ALERT_SOUND_DURATION / 1000);
    oscillator.stop(ctx.currentTime + ALERT_SOUND_DURATION / 1000);
  } catch {
    // Audio not supported — fail silently
  }
}

function showBrowserNotification(alert: TriggeredAlert) {
  if (Notification.permission !== 'granted') return;
  const name = SYMBOL_NAMES[alert.symbol] || alert.symbol;
  const direction = alert.condition === 'ABOVE' ? 'above' : 'below';
  new Notification(`Price Alert: ${name}`, {
    body: `${alert.symbol} is now ${direction} ${formatPrice(alert.targetPrice)} (current: ${formatPrice(alert.currentPrice)})`,
    icon: '/favicon.ico',
    tag: `alert-${alert.id}`,
  });
}

export function AlertToast({ alerts, onDismiss }: AlertToastProps) {
  return (
    <div className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 max-w-sm">
      {alerts.map((alert) => (
        <AlertToastItem key={alert.id} alert={alert} onDismiss={onDismiss} />
      ))}
    </div>
  );
}

function AlertToastItem({ alert, onDismiss }: { alert: TriggeredAlert; onDismiss: (id: number) => void }) {
  useEffect(() => {
    const timer = setTimeout(() => onDismiss(alert.id), AUTO_DISMISS_MS);
    return () => clearTimeout(timer);
  }, [alert.id, onDismiss]);

  const name = SYMBOL_NAMES[alert.symbol] || alert.symbol;
  const isAbove = alert.condition === 'ABOVE';

  return (
    <div className="glass-card border border-accent/40 p-4 shadow-lg shadow-accent/10 animate-slide-in">
      <div className="flex items-start gap-3">
        <div className={`p-2 rounded-lg ${isAbove ? 'bg-gain/20' : 'bg-loss/20'}`}>
          <Bell className={`h-4 w-4 ${isAbove ? 'text-gain' : 'text-loss'}`} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-sm font-semibold text-text-primary">{name}</span>
            <span className={`flex items-center gap-0.5 text-xs font-medium px-1.5 py-0.5 rounded ${
              isAbove ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'
            }`}>
              {isAbove ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
              {alert.condition}
            </span>
          </div>
          <p className="text-xs text-text-secondary">
            Target: <span className="font-mono text-text-primary">{formatPrice(alert.targetPrice)}</span>
            {' | '}
            Now: <span className="font-mono text-accent">{formatPrice(alert.currentPrice)}</span>
          </p>
          {alert.note && (
            <p className="text-xs text-text-secondary mt-1 truncate">{alert.note}</p>
          )}
        </div>
        <button
          onClick={() => onDismiss(alert.id)}
          className="p-1 text-text-secondary hover:text-text-primary transition-colors"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

export function useAlertNotifications() {
  const [toastAlerts, setToastAlerts] = useState<TriggeredAlert[]>([]);

  // Request notification permission on mount
  useEffect(() => {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission();
    }
  }, []);

  const handleAlert = useCallback((data: TriggeredAlert) => {
    setToastAlerts((prev) => [...prev, data]);
    playAlertSound();
    showBrowserNotification(data);
  }, []);

  const dismissAlert = useCallback((id: number) => {
    setToastAlerts((prev) => prev.filter((a) => a.id !== id));
  }, []);

  return { toastAlerts, handleAlert, dismissAlert };
}
