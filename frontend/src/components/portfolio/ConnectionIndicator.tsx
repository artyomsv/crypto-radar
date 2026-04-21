import type { ConnectionState } from '@/hooks/useExecutionStream';

interface Props {
  state: ConnectionState;
  secondsSinceUpdate: number;
  environment: 'DEMO' | 'MAINNET';
}

export function ConnectionIndicator({ state, secondsSinceUpdate, environment }: Props) {
  const color =
    state === 'connected' ? '#4ade80' :
    state === 'reconnecting' ? '#f7a600' :
    '#ef4444';
  const label =
    state === 'connected' ? `Connected · ${environment} · v5` :
    state === 'reconnecting' ? `Reconnecting… (last update ${secondsSinceUpdate}s ago)` :
    'Disconnected (polling fallback)';
  const pulse = state === 'reconnecting' ? 'animate-pulse' : '';
  return (
    <div className="text-[10px]" style={{ color }}>
      <span className={`mr-1 inline-block h-[6px] w-[6px] rounded-full ${pulse}`} style={{ backgroundColor: color }} />
      {label}
    </div>
  );
}
