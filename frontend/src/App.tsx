import { lazy, Suspense } from 'react';
import { Routes, Route } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { Header } from './components/layout/Header';
import { AlertToast, useAlertNotifications } from './components/dashboard/AlertToast';
import { useWebSocket } from './hooks/useWebSocket';

// Route-level code splitting: each page is a separate chunk so a given route
// (e.g. /config) only downloads + parses its own code plus shared vendor deps,
// instead of the whole app bundle (which includes the charting library and
// every other dashboard). Named exports are mapped to default for React.lazy.
const SignalDashboard = lazy(() => import('./components/dashboard/SignalDashboard').then(m => ({ default: m.SignalDashboard })));
const Dashboard = lazy(() => import('./components/dashboard/Dashboard').then(m => ({ default: m.Dashboard })));
const CryptoDetailView = lazy(() => import('./components/dashboard/CryptoDetailView').then(m => ({ default: m.CryptoDetailView })));
const WhaleTracker = lazy(() => import('./components/dashboard/WhaleTracker').then(m => ({ default: m.WhaleTracker })));
const DerivativesDashboard = lazy(() => import('./components/dashboard/DerivativesDashboard').then(m => ({ default: m.DerivativesDashboard })));
const AnalyticsDashboard = lazy(() => import('./components/dashboard/AnalyticsDashboard').then(m => ({ default: m.AnalyticsDashboard })));
const Screener = lazy(() => import('./components/dashboard/Screener').then(m => ({ default: m.Screener })));
const MultiChart = lazy(() => import('./components/dashboard/MultiChart').then(m => ({ default: m.MultiChart })));
const PortfolioTracker = lazy(() => import('./components/dashboard/PortfolioTracker').then(m => ({ default: m.PortfolioTracker })));
const ExchangeAccountsSection = lazy(() => import('./components/portfolio/ExchangeAccountsSection').then(m => ({ default: m.ExchangeAccountsSection })));
const CryptoConfig = lazy(() => import('./components/dashboard/CryptoConfig').then(m => ({ default: m.CryptoConfig })));
const BacktestPage = lazy(() => import('./components/backtest/BacktestPage').then(m => ({ default: m.BacktestPage })));
const SignalConfigPage = lazy(() => import('./components/config/SignalConfigPage').then(m => ({ default: m.SignalConfigPage })));
const OptionsPage = lazy(() => import('./components/options/OptionsPage').then(m => ({ default: m.OptionsPage })));

function RouteFallback() {
  return (
    <div className="flex items-center justify-center h-[60vh]">
      <Loader2 className="h-8 w-8 text-accent animate-spin" />
    </div>
  );
}

export default function App() {
  const { toastAlerts, handleAlert, dismissAlert } = useAlertNotifications();

  useWebSocket({
    onAlerts: handleAlert,
  });

  return (
    <div className="min-h-screen bg-background">
      <Header />
      <main className="container mx-auto px-4 py-6 max-w-[1600px]">
        <Suspense fallback={<RouteFallback />}>
          <Routes>
            <Route path="/signals" element={<SignalDashboard />} />
            <Route path="/" element={<Dashboard />} />
            <Route path="/crypto/:symbol" element={<CryptoDetailView />} />
            <Route path="/whales" element={<WhaleTracker />} />
            <Route path="/derivatives" element={<DerivativesDashboard />} />
            <Route path="/analytics" element={<AnalyticsDashboard />} />
            <Route path="/screener" element={<Screener />} />
            <Route path="/compare" element={<MultiChart />} />
            <Route
              path="/portfolio"
              element={
                <div className="flex flex-col gap-6">
                  <PortfolioTracker />
                  <ExchangeAccountsSection />
                </div>
              }
            />
            <Route path="/config" element={<CryptoConfig />} />
            <Route path="/backtest" element={<BacktestPage />} />
            <Route path="/signal-config" element={<SignalConfigPage />} />
            <Route path="/options" element={<OptionsPage />} />
          </Routes>
        </Suspense>
      </main>
      <AlertToast alerts={toastAlerts} onDismiss={dismissAlert} />
    </div>
  );
}
