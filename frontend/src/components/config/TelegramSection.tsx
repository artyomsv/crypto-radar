import { useState } from 'react';
import { Loader2, Send, Check, X } from 'lucide-react';
import { api } from '@/lib/api';
import type { ExecutionSettings, TelegramTestResult } from '@/types';
import { cn } from '@/lib/utils';

/**
 * Exact ExecutionEventType names emitted by trade-execution-service. The server
 * validates the submitted set against the same enum, so these are the real
 * values, not display strings. Labels are derived, not hand-maintained.
 */
const EXECUTION_EVENT_TYPES = [
  'ORDER_FILLED', 'ORDER_PLACED', 'ORDER_REJECTED', 'ORDER_CANCELLED',
  'POSITION_CLOSED', 'TRAIL_UPDATED', 'FLIP_CLOSE_TRIGGERED',
  'KILL_SWITCH_TOGGLED', 'AUTO_TRADE_TOGGLED', 'DAILY_HALT_ENTERED', 'DAILY_HALT_EXITED',
  'RECONCILE_CLOSED_EXTERNALLY', 'RECONCILE_ORPHAN_DETECTED', 'RECONCILE_DRIFT_DETECTED',
  'AUTH_FAILURE', 'BYBIT_CIRCUIT_OPEN', 'WS_DISCONNECTED', 'WS_RECONNECTED',
  'SIGNAL_ACCEPTED', 'SIGNAL_BLOCKED_KILL_SWITCH', 'SIGNAL_BLOCKED_AUTO_TRADE_OFF',
  'SIGNAL_BLOCKED_MAX_CONCURRENT', 'SIGNAL_BLOCKED_DAILY_HALT', 'SIGNAL_BLOCKED_DEDUP',
  'SIGNAL_BLOCKED_SIGNAL_AGE', 'SIGNAL_BLOCKED_PERSISTENCE', 'SIGNAL_BLOCKED_INSUFFICIENT_MARGIN',
];

function labelFor(eventType: string): string {
  return eventType.toLowerCase().replace(/_/g, ' ');
}

interface Props {
  enabled: boolean;
  chatId: string | null;
  configured: boolean;
  botTokenDraft: string | null;
  notifiedEvents: string[];
  notifyOptions: boolean;
  onPatch: (patch: Partial<ExecutionSettings>) => void;
}

export function TelegramSection({
  enabled, chatId, configured, botTokenDraft, notifiedEvents, notifyOptions, onPatch,
}: Props) {
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<TelegramTestResult | null>(null);

  const selected = new Set(notifiedEvents);

  const toggleEvent = (eventType: string) => {
    const next = new Set(selected);
    if (next.has(eventType)) next.delete(eventType);
    else next.add(eventType);
    onPatch({ telegramNotifiedEvents: Array.from(next) });
  };

  const handleTest = async () => {
    setTesting(true);
    setResult(null);
    const res = await api.testTelegramNotification(botTokenDraft, chatId);
    setResult(res);
    setTesting(false);
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <label className="text-[11px] text-text-secondary">Enabled</label>
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => onPatch({ telegramEnabled: e.target.checked })}
          className="h-3.5 w-3.5 accent-[var(--color-accent)]"
        />
        <span className="text-[10px] text-text-secondary">
          {configured ? 'Bot token saved' : 'No bot token saved yet'}
        </span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-3">
        <div className="space-y-1">
          <label className="text-[11px] text-text-secondary" title="From @BotFather. Stored encrypted; leave blank to keep the saved token.">
            Bot token
          </label>
          <input
            type="password"
            autoComplete="off"
            value={botTokenDraft ?? ''}
            placeholder={configured ? '•••••• (saved — leave blank to keep)' : '123456:ABC-DEF...'}
            onChange={(e) => onPatch({ telegramBotToken: e.target.value || null })}
            className="w-full px-2 py-0.5 rounded bg-surface border border-surface-border text-xs font-mono text-text-primary focus:outline-none focus:border-accent/60"
          />
        </div>
        <div className="space-y-1">
          <label className="text-[11px] text-text-secondary" title="Numeric chat ID or @channelusername the bot posts to. Get it from @userinfobot.">
            Chat ID
          </label>
          <input
            type="text"
            autoComplete="off"
            value={chatId ?? ''}
            placeholder="e.g. 123456789 or @mychannel"
            onChange={(e) => onPatch({ telegramChatId: e.target.value || null })}
            className="w-full px-2 py-0.5 rounded bg-surface border border-surface-border text-xs font-mono text-text-primary focus:outline-none focus:border-accent/60"
          />
        </div>
      </div>

      <label className="flex items-center gap-2 text-[11px] text-text-secondary cursor-pointer" title="Alert when options-service detects a fresh strangle/straddle setup above its confidence threshold.">
        <input
          type="checkbox"
          checked={notifyOptions}
          onChange={(e) => onPatch({ telegramNotifyOptions: e.target.checked })}
          className="h-3.5 w-3.5 accent-[var(--color-accent)]"
        />
        Option trade opportunities (volatility watchlist)
      </label>

      <div className="space-y-1.5">
        <label className="text-[11px] text-text-secondary">Notify on execution events</label>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1 max-h-44 overflow-y-auto pr-1 rounded border border-surface-border p-2">
          {EXECUTION_EVENT_TYPES.map((eventType) => (
            <label key={eventType} className="flex items-center gap-1.5 text-[10px] text-text-secondary cursor-pointer">
              <input
                type="checkbox"
                checked={selected.has(eventType)}
                onChange={() => toggleEvent(eventType)}
                className="h-3 w-3 accent-[var(--color-accent)]"
              />
              {labelFor(eventType)}
            </label>
          ))}
        </div>
      </div>

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleTest}
          disabled={testing}
          className="flex items-center gap-1.5 px-3 py-1 rounded text-xs font-medium border border-accent/40 text-accent hover:bg-accent/20 transition-colors disabled:opacity-50"
        >
          {testing ? <Loader2 className="h-3 w-3 animate-spin" /> : <Send className="h-3 w-3" />}
          Send test message
        </button>
        {result && (
          <span className={cn('flex items-center gap-1 text-[11px]', result.ok ? 'text-gain' : 'text-loss')}>
            {result.ok ? <Check className="h-3 w-3" /> : <X className="h-3 w-3" />}
            {result.ok ? 'Test message sent' : (result.error ?? 'Failed')}
          </span>
        )}
      </div>
      <p className="text-[10px] text-text-secondary">
        Test uses the typed token/chat ID if present, otherwise the saved ones. Save to persist changes.
      </p>
    </div>
  );
}
