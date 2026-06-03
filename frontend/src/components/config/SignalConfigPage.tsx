import { useCallback, useEffect, useReducer } from 'react';
import { Loader2, SlidersHorizontal } from 'lucide-react';
import { api } from '@/lib/api';
import type { SignalConfig, SignalConfigVersion } from '@/types';
import { ConfigPageHeader } from './ConfigPageHeader';
import { VersionHistoryList } from './VersionHistoryList';
import { ConfigEditor } from './ConfigEditor';
import { ExecutionGatesPanel } from './ExecutionGatesPanel';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

interface PageState {
  versions: SignalConfigVersion[];
  selectedVersion: SignalConfigVersion | null;
  editorConfig: SignalConfig | null;
  /** true when editor config diverges from selectedVersion.config */
  isDirty: boolean;
  isLoadingVersions: boolean;
  isLoadingVersion: boolean;
  isSaving: boolean;
  isActivating: boolean;
  errorBanner: string | null;
}

type PageAction =
  | { type: 'versions_loaded'; versions: SignalConfigVersion[] }
  | { type: 'version_selected'; version: SignalConfigVersion }
  | { type: 'editor_changed'; config: SignalConfig }
  | { type: 'saving_start' }
  | { type: 'saving_done'; newVersion: SignalConfigVersion; versions: SignalConfigVersion[] }
  | { type: 'activating_start' }
  | { type: 'activating_done'; updated: SignalConfigVersion; versions: SignalConfigVersion[] }
  | { type: 'loading_version_start' }
  | { type: 'error'; message: string }
  | { type: 'clear_error' };

function reducer(state: PageState, action: PageAction): PageState {
  switch (action.type) {
    case 'versions_loaded':
      return { ...state, versions: action.versions, isLoadingVersions: false };
    case 'version_selected':
      return {
        ...state,
        selectedVersion: action.version,
        editorConfig: action.version.config,
        isDirty: false,
        isLoadingVersion: false,
      };
    case 'editor_changed':
      return { ...state, editorConfig: action.config, isDirty: true };
    case 'saving_start':
      return { ...state, isSaving: true, errorBanner: null };
    case 'saving_done':
      return {
        ...state,
        isSaving: false,
        versions: action.versions,
        selectedVersion: action.newVersion,
        editorConfig: action.newVersion.config,
        isDirty: false,
      };
    case 'activating_start':
      return { ...state, isActivating: true, errorBanner: null };
    case 'activating_done':
      return {
        ...state,
        isActivating: false,
        versions: action.versions,
        selectedVersion: action.updated,
        editorConfig: action.updated.config,
        isDirty: false,
      };
    case 'loading_version_start':
      return { ...state, isLoadingVersion: true };
    case 'error':
      return { ...state, isSaving: false, isActivating: false, errorBanner: action.message };
    case 'clear_error':
      return { ...state, errorBanner: null };
    default:
      return state;
  }
}

const INITIAL_STATE: PageState = {
  versions: [],
  selectedVersion: null,
  editorConfig: null,
  isDirty: false,
  isLoadingVersions: true,
  isLoadingVersion: false,
  isSaving: false,
  isActivating: false,
  errorBanner: null,
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function SignalConfigPage() {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE);

  const activeVersion = state.versions.find((v) => v.isActive) ?? null;

  const loadVersions = useCallback(async () => {
    const versions = await api.listSignalConfigVersions(50, 0);
    if (!versions) return;
    dispatch({ type: 'versions_loaded', versions });
    return versions;
  }, []);

  // On mount: load versions, then select active
  useEffect(() => {
    loadVersions().then((versions) => {
      if (!versions || versions.length === 0) return;
      const active = versions.find((v) => v.isActive);
      if (active) dispatch({ type: 'version_selected', version: active });
    });
  }, [loadVersions]);

  const handleSelectVersion = useCallback(async (v: SignalConfigVersion) => {
    dispatch({ type: 'loading_version_start' });
    const full = await api.getSignalConfigVersion(v.id);
    if (!full) {
      dispatch({ type: 'error', message: `Failed to load version ${v.version}` });
      return;
    }
    dispatch({ type: 'version_selected', version: full });
  }, []);

  const handleSave = useCallback(async (description: string) => {
    if (!state.editorConfig) return;
    dispatch({ type: 'saving_start' });
    try {
      const newVersion = await api.saveSignalConfigVersion(
        state.editorConfig,
        description,
        state.selectedVersion?.id,
      );
      const versions = await api.listSignalConfigVersions(50, 0);
      dispatch({ type: 'saving_done', newVersion, versions: versions ?? [] });
    } catch (e) {
      dispatch({ type: 'error', message: e instanceof Error ? e.message : 'Save failed' });
    }
  }, [state.editorConfig, state.selectedVersion]);

  const handleActivate = useCallback(async () => {
    if (!state.selectedVersion) return;
    dispatch({ type: 'activating_start' });
    try {
      const updated = await api.activateSignalConfigVersion(state.selectedVersion.id);
      // Refresh list so active badge flips correctly
      const versions = await api.listSignalConfigVersions(50, 0);
      dispatch({ type: 'activating_done', updated, versions: versions ?? [] });
    } catch (e) {
      dispatch({ type: 'error', message: e instanceof Error ? e.message : 'Activation failed' });
    }
  }, [state.selectedVersion]);

  const handleResetToDefaults = useCallback(async () => {
    const v1 = await api.getSignalConfigVersion(1);
    if (!v1) {
      dispatch({ type: 'error', message: 'Could not load v1 defaults' });
      return;
    }
    dispatch({ type: 'editor_changed', config: v1.config });
  }, []);

  const weightsValid =
    state.editorConfig !== null &&
    Math.abs(Object.values(state.editorConfig.weights).reduce((a, b) => a + b, 0) - 1.0) <= 0.001;

  if (state.isLoadingVersions) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <Loader2 className="h-8 w-8 text-accent animate-spin" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Page title */}
      <div className="flex items-center gap-2">
        <SlidersHorizontal className="h-5 w-5 text-accent" />
        <h1 className="text-xl font-bold text-text-primary">Signal Engine Config</h1>
      </div>

      {/* Error banner */}
      {state.errorBanner && (
        <div className="flex items-center justify-between px-4 py-3 rounded border bg-loss/10 border-loss/40 text-loss text-sm">
          <span>{state.errorBanner}</span>
          <button onClick={() => dispatch({ type: 'clear_error' })} className="ml-4 text-loss/70 hover:text-loss">✕</button>
        </div>
      )}

      {/* Header controls */}
      <ConfigPageHeader
        activeVersion={activeVersion}
        selectedVersion={state.selectedVersion}
        isSaving={state.isSaving}
        isActivating={state.isActivating}
        isDirty={state.isDirty}
        onSave={handleSave}
        onActivate={handleActivate}
        onResetToDefaults={handleResetToDefaults}
      />

      {/* Execution gates — separate process, separate save flow */}
      <ExecutionGatesPanel />

      {/* Two-column layout */}
      <div className="grid grid-cols-[220px_1fr] gap-4 items-start">
        {/* Left rail — version history */}
        <div className="sticky top-20">
          <VersionHistoryList
            versions={state.versions}
            selectedId={state.selectedVersion?.id ?? null}
            activeId={activeVersion?.id ?? null}
            onSelect={handleSelectVersion}
          />
        </div>

        {/* Right — editor */}
        <div className="glass-card p-5 min-h-[400px]">
          {state.isLoadingVersion ? (
            <div className="flex items-center justify-center h-40">
              <Loader2 className="h-6 w-6 text-accent animate-spin" />
            </div>
          ) : state.editorConfig ? (
            <>
              {!weightsValid && (
                <div className="mb-4 px-3 py-2 rounded border bg-loss/10 border-loss/40 text-loss text-xs">
                  Weights do not sum to 1.0 — fix before saving.
                </div>
              )}
              <ConfigEditor
                config={state.editorConfig}
                onChange={(cfg) => dispatch({ type: 'editor_changed', config: cfg })}
              />
            </>
          ) : (
            <div className="flex items-center justify-center h-40 text-text-secondary text-sm">
              Select a version from the list to view or edit its config.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
