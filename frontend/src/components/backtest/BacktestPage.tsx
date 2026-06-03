import { useReducer, useCallback } from 'react';
import { api } from '@/lib/api';
import type { BacktestRunDetail, BacktestRun } from '@/types';
import { BacktestForm } from './BacktestForm';
import { BacktestSummaryCard } from './BacktestSummaryCard';
import { EquityCurveChart } from './EquityCurveChart';
import { TradeDiffTable } from './TradeDiffTable';
import { RunHistoryList } from './RunHistoryList';
import { FlaskConical, AlertTriangle } from 'lucide-react';

interface PageState {
  selectedVersionId: number | null;
  isRunning: boolean;
  runError: string | null;
  currentDetail: BacktestRunDetail | null;
  runHistory: BacktestRun[];
}

type PageAction =
  | { type: 'SET_VERSION'; id: number }
  | { type: 'RUN_START' }
  | { type: 'RUN_SUCCESS'; detail: BacktestRunDetail }
  | { type: 'RUN_ERROR'; error: string }
  | { type: 'LOAD_DETAIL'; detail: BacktestRunDetail }
  | { type: 'SET_HISTORY'; runs: BacktestRun[] };

const initialState: PageState = {
  selectedVersionId: null,
  isRunning: false,
  runError: null,
  currentDetail: null,
  runHistory: [],
};

function reducer(state: PageState, action: PageAction): PageState {
  switch (action.type) {
    case 'SET_VERSION':
      return { ...state, selectedVersionId: action.id };
    case 'RUN_START':
      return { ...state, isRunning: true, runError: null };
    case 'RUN_SUCCESS':
      return {
        ...state,
        isRunning: false,
        currentDetail: action.detail,
        runHistory: [action.detail, ...state.runHistory.filter(r => r.id !== action.detail.id)],
      };
    case 'RUN_ERROR':
      return { ...state, isRunning: false, runError: action.error };
    case 'LOAD_DETAIL':
      return { ...state, currentDetail: action.detail };
    case 'SET_HISTORY':
      return { ...state, runHistory: action.runs };
    default:
      return state;
  }
}

export function BacktestPage() {
  const [state, dispatch] = useReducer(reducer, initialState);

  const handleVersionChange = useCallback((id: number) => {
    dispatch({ type: 'SET_VERSION', id });
    api.listBacktestRuns(id, 20).then(runs => {
      if (runs) dispatch({ type: 'SET_HISTORY', runs });
    });
  }, []);

  const handleRunBacktest = useCallback(async (
      versionId: number,
      periodStart: string,
      periodEnd: string,
      alignmentFloor: number | null,
  ) => {
    dispatch({ type: 'RUN_START' });
    try {
      const run = await api.runBacktest(versionId, periodStart, periodEnd, 1, alignmentFloor);
      const detail = await api.getBacktestRun(run.id);
      if (!detail) throw new Error('Failed to load run detail');
      dispatch({ type: 'RUN_SUCCESS', detail });
    } catch (e) {
      dispatch({ type: 'RUN_ERROR', error: e instanceof Error ? e.message : 'Backtest failed' });
    }
  }, []);

  const handleSelectHistoryRun = useCallback(async (run: BacktestRun) => {
    const detail = await api.getBacktestRun(run.id);
    if (detail) dispatch({ type: 'LOAD_DETAIL', detail });
  }, []);

  const { selectedVersionId, isRunning, runError, currentDetail, runHistory } = state;

  return (
    <div className="flex flex-col gap-6">
      {/* Page header */}
      <div className="flex items-center gap-2">
        <FlaskConical className="h-5 w-5 text-accent" />
        <h2 className="text-lg font-semibold text-text-primary">Backtest</h2>
        <span className="text-xs text-text-secondary">Re-score historical signal outcomes against any config version</span>
      </div>

      {/* Form row */}
      <BacktestForm
        onRunStarted={handleRunBacktest}
        isRunning={isRunning}
        selectedVersionId={selectedVersionId}
        onVersionChange={handleVersionChange}
      />

      {/* Error banner */}
      {runError && (
        <div className="flex items-center gap-2 px-4 py-3 rounded border border-loss/40 bg-loss/10 text-loss text-sm">
          <AlertTriangle className="h-4 w-4 flex-shrink-0" />
          {runError}
        </div>
      )}

      {/* Empty state */}
      {!currentDetail && !runError && (
        <div className="glass-card p-12 text-center">
          <FlaskConical className="h-8 w-8 text-muted mx-auto mb-3" />
          <p className="text-sm text-text-secondary">Pick a config version and period, then click Run Backtest.</p>
          <p className="text-xs text-muted mt-1">Results will show metrics, equity curve, and per-trade breakdown.</p>
        </div>
      )}

      {/* Results layout */}
      {currentDetail && (
        <div className="flex flex-col xl:flex-row gap-6">
          {/* Main results column */}
          <div className="flex flex-col gap-6 flex-1 min-w-0">
            <BacktestSummaryCard run={currentDetail} />
            <EquityCurveChart trades={currentDetail.trades} originalTotalR={currentDetail.originalTotalR} />
            <TradeDiffTable trades={currentDetail.trades} />
          </div>

          {/* Run history right rail */}
          <div className="w-full xl:w-64 flex-shrink-0">
            <RunHistoryList
              runs={runHistory}
              activeRunId={currentDetail.id}
              onSelect={handleSelectHistoryRun}
            />
          </div>
        </div>
      )}
    </div>
  );
}
