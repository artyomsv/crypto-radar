import { Routes, Route } from 'react-router-dom';
import { Header } from './components/layout/Header';
import { Dashboard } from './components/dashboard/Dashboard';
import { CryptoDetailView } from './components/dashboard/CryptoDetailView';
import { WhaleTracker } from './components/dashboard/WhaleTracker';
import { CryptoConfig } from './components/dashboard/CryptoConfig';
import { DerivativesDashboard } from './components/dashboard/DerivativesDashboard';
import { AnalyticsDashboard } from './components/dashboard/AnalyticsDashboard';
import { SignalDashboard } from './components/dashboard/SignalDashboard';
import { Screener } from './components/dashboard/Screener';
import { MultiChart } from './components/dashboard/MultiChart';
import { PortfolioTracker } from './components/dashboard/PortfolioTracker';
import { ExchangeAccountsSection } from './components/portfolio/ExchangeAccountsSection';
import { AlertToast, useAlertNotifications } from './components/dashboard/AlertToast';
import { useWebSocket } from './hooks/useWebSocket';

export default function App() {
  const { toastAlerts, handleAlert, dismissAlert } = useAlertNotifications();

  useWebSocket({
    onAlerts: handleAlert,
  });

  return (
    <div className="min-h-screen bg-background">
      <Header />
      <main className="container mx-auto px-4 py-6 max-w-[1600px]">
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
        </Routes>
      </main>
      <AlertToast alerts={toastAlerts} onDismiss={dismissAlert} />
    </div>
  );
}
