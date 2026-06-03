import { useEffect, useState } from 'react';
import { Loader2, Save, MessageSquare } from 'lucide-react';
import { api } from '@/lib/api';
import type { ExecutionSettings } from '@/types';
import { TelegramSection } from '@/components/config/TelegramSection';
import { cn } from '@/lib/utils';

/**
 * Self-contained Telegram-notification editor for the Settings page. Loads the
 * singleton execution_settings row, mutates only the Telegram fields, and PUTs
 * the whole row back (the endpoint replaces the single row). Kept independent
 * of the Execution Gates panel so the global row has exactly one editor.
 */
export function TelegramNotificationsPanel() {
  const [settings, setSettings] = useState<ExecutionSettings | null>(null);
  const [original, setOriginal] = useState<ExecutionSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);

  useEffect(() => {
    api.getExecutionSettings().then((s) => {
      if (s) {
        setSettings(s);
        setOriginal(s);
      }
      setLoading(false);
    });
  }, []);

  const isDirty = settings !== null && original !== null
    && JSON.stringify(settings) !== JSON.stringify(original);

  const patch = (p: Partial<ExecutionSettings>) =>
    setSettings((s) => (s ? { ...s, ...p } : s));

  const handleSave = async () => {
    if (!settings) return;
    setSaving(true);
    setError(null);
    try {
      const next = await api.updateExecutionSettings(settings);
      setSettings(next);
      setOriginal(next);
      setSavedAt(new Date());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="glass-card p-5 flex items-center justify-center h-24">
        <Loader2 className="h-5 w-5 text-accent animate-spin" />
      </div>
    );
  }

  if (!settings) {
    return (
      <div className="glass-card p-5 text-sm text-text-secondary">
        Telegram settings unavailable — trade-execution-service unreachable.
      </div>
    );
  }

  return (
    <div className="glass-card p-5 space-y-4">
      <div className="flex items-center justify-between border-b border-surface-border pb-2">
        <h2 className="text-sm font-semibold text-text-primary flex items-center gap-2">
          <MessageSquare className="h-4 w-4 text-accent" />
          Telegram Notifications
          <span className="text-[10px] text-text-secondary font-normal">
            (trade-execution-service · 30s hot-reload)
          </span>
        </h2>
        <div className="flex items-center gap-3">
          {savedAt && !isDirty && (
            <span className="text-[10px] text-gain">Saved {savedAt.toLocaleTimeString()}</span>
          )}
          <button
            onClick={handleSave}
            disabled={!isDirty || saving}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1 rounded text-xs font-medium border transition-colors',
              isDirty && !saving
                ? 'bg-accent/20 text-accent border-accent/40 hover:bg-accent/30'
                : 'bg-surface/40 text-text-secondary border-surface-border cursor-not-allowed'
            )}
          >
            {saving ? <Loader2 className="h-3 w-3 animate-spin" /> : <Save className="h-3 w-3" />}
            Save
          </button>
        </div>
      </div>

      {error && (
        <div className="px-3 py-2 rounded border bg-loss/10 border-loss/40 text-loss text-xs">
          {error}
        </div>
      )}

      <TelegramSection
        enabled={settings.telegramEnabled}
        chatId={settings.telegramChatId}
        configured={settings.telegramConfigured}
        botTokenDraft={settings.telegramBotToken}
        notifiedEvents={settings.telegramNotifiedEvents}
        notifyOptions={settings.telegramNotifyOptions}
        onPatch={patch}
      />
    </div>
  );
}
