import { useState } from 'react';
import { CheckCircle2, RefreshCw, Save, Zap } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { SignalConfigVersion } from '@/types';

interface ConfigPageHeaderProps {
  activeVersion: SignalConfigVersion | null;
  selectedVersion: SignalConfigVersion | null;
  isSaving: boolean;
  isActivating: boolean;
  isDirty: boolean;
  onSave: (description: string) => void;
  onActivate: () => void;
  onResetToDefaults: () => void;
}

export function ConfigPageHeader({
  activeVersion,
  selectedVersion,
  isSaving,
  isActivating,
  isDirty,
  onSave,
  onActivate,
  onResetToDefaults,
}: ConfigPageHeaderProps) {
  const [savePromptOpen, setSavePromptOpen] = useState(false);
  const [description, setDescription] = useState('');
  const [activateConfirmOpen, setActivateConfirmOpen] = useState(false);

  const isViewingActive =
    activeVersion !== null &&
    selectedVersion !== null &&
    selectedVersion.id === activeVersion.id;

  const handleSaveSubmit = () => {
    if (!description.trim()) return;
    onSave(description.trim());
    setSavePromptOpen(false);
    setDescription('');
  };

  const handleActivateConfirm = () => {
    onActivate();
    setActivateConfirmOpen(false);
  };

  return (
    <div className="glass-card p-4 flex flex-wrap items-center gap-4">
      {/* Active version badge */}
      <div className="flex-1 min-w-0">
        <p className="text-xs text-text-secondary mb-0.5">Active version</p>
        {activeVersion ? (
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded border bg-gain/10 text-gain border-gain/40 text-[11px] font-semibold">
              <CheckCircle2 className="h-3 w-3" />
              v{activeVersion.version}
            </span>
            <span className="text-sm text-text-primary truncate">{activeVersion.description}</span>
          </div>
        ) : (
          <span className="text-sm text-text-secondary">—</span>
        )}
      </div>

      {/* Actions */}
      <div className="flex items-center gap-2 flex-wrap">
        <button
          onClick={onResetToDefaults}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded text-xs text-text-secondary border border-surface-border hover:text-accent hover:border-accent/40 transition-colors"
          title="Load v1 defaults into editor (does not save)"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Reset to defaults
        </button>

        {!isViewingActive && selectedVersion !== null && (
          <button
            onClick={() => setActivateConfirmOpen(true)}
            disabled={isActivating}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-semibold transition-colors',
              'bg-accent/10 text-accent border border-accent/40 hover:bg-accent/20',
              isActivating && 'opacity-50 cursor-not-allowed'
            )}
          >
            <Zap className="h-3.5 w-3.5" />
            {isActivating ? 'Activating…' : `Activate v${selectedVersion.version}`}
          </button>
        )}

        <button
          onClick={() => setSavePromptOpen(true)}
          disabled={isSaving}
          className={cn(
            'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-semibold transition-colors',
            'bg-gain/10 text-gain border border-gain/40 hover:bg-gain/20',
            isSaving && 'opacity-50 cursor-not-allowed'
          )}
        >
          <Save className="h-3.5 w-3.5" />
          {isSaving ? 'Saving…' : isDirty ? 'Save as new version *' : 'Save as new version'}
        </button>
      </div>

      {/* Save prompt overlay */}
      {savePromptOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="glass-card p-6 w-full max-w-sm space-y-4">
            <h3 className="text-sm font-semibold text-text-primary">Save as new version</h3>
            <input
              autoFocus
              type="text"
              placeholder="Description (required)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSaveSubmit()}
              className="w-full px-3 py-2 rounded bg-surface border border-surface-border text-sm text-text-primary placeholder:text-text-secondary focus:outline-none focus:border-accent/60"
            />
            <div className="flex gap-2 justify-end">
              <button
                onClick={() => { setSavePromptOpen(false); setDescription(''); }}
                className="px-3 py-1.5 rounded text-xs text-text-secondary border border-surface-border hover:text-accent transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveSubmit}
                disabled={!description.trim()}
                className={cn(
                  'px-3 py-1.5 rounded text-xs font-semibold transition-colors',
                  'bg-gain/10 text-gain border border-gain/40 hover:bg-gain/20',
                  !description.trim() && 'opacity-40 cursor-not-allowed'
                )}
              >
                Save
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Activate confirm overlay */}
      {activateConfirmOpen && selectedVersion && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="glass-card p-6 w-full max-w-sm space-y-4">
            <h3 className="text-sm font-semibold text-text-primary">Activate version?</h3>
            <p className="text-xs text-text-secondary">
              This will activate <span className="text-text-primary font-semibold">v{selectedVersion.version}</span>{' '}
              &ldquo;{selectedVersion.description}&rdquo; and reload the signal engine within ~30s.
            </p>
            <div className="flex gap-2 justify-end">
              <button
                onClick={() => setActivateConfirmOpen(false)}
                className="px-3 py-1.5 rounded text-xs text-text-secondary border border-surface-border hover:text-accent transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleActivateConfirm}
                className="px-3 py-1.5 rounded text-xs font-semibold bg-accent/10 text-accent border border-accent/40 hover:bg-accent/20 transition-colors"
              >
                Activate
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
