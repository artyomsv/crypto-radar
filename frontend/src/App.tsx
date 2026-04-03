import { Routes, Route } from 'react-router-dom';
import { Header } from './components/layout/Header';
import { Dashboard } from './components/dashboard/Dashboard';
import { CryptoDetailView } from './components/dashboard/CryptoDetailView';
import { WhaleTracker } from './components/dashboard/WhaleTracker';
import { CryptoConfig } from './components/dashboard/CryptoConfig';
import { DerivativesDashboard } from './components/dashboard/DerivativesDashboard';

export default function App() {
  return (
    <div className="min-h-screen bg-background">
      <Header />
      <main className="container mx-auto px-4 py-6 max-w-[1600px]">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/crypto/:symbol" element={<CryptoDetailView />} />
          <Route path="/whales" element={<WhaleTracker />} />
          <Route path="/derivatives" element={<DerivativesDashboard />} />
          <Route path="/config" element={<CryptoConfig />} />
        </Routes>
      </main>
    </div>
  );
}
