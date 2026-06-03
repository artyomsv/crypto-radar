// Route paths
export const ROUTES = {
  DASHBOARD: '/',
  SIGNALS: '/signals',
  SCREENER: '/screener',
  WHALES: '/whales',
  DERIVATIVES: '/derivatives',
  ANALYTICS: '/analytics',
  COMPARE: '/compare',
  PORTFOLIO: '/portfolio',
  CONFIG: '/config',
  SIGNAL_CONFIG: '/signal-config',
} as const;

// Query/cache keys
export const QUERY_KEYS = {
  SIGNAL_CONFIG_ACTIVE: 'signal-config-active',
  SIGNAL_CONFIG_VERSIONS: 'signal-config-versions',
  SIGNAL_CONFIG_VERSION: 'signal-config-version',
  BACKTEST_RUNS: 'backtest-runs',
} as const;
