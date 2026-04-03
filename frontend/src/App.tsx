import { Routes, Route } from 'react-router-dom';
import { Header } from './components/layout/Header';
import { Dashboard } from './components/dashboard/Dashboard';
import { CryptoDetailView } from './components/dashboard/CryptoDetailView';

export default function App() {
  return (
    <div className="min-h-screen bg-background">
      <Header />
      <main className="container mx-auto px-4 py-6 max-w-[1600px]">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/crypto/:symbol" element={<CryptoDetailView />} />
        </Routes>
      </main>
    </div>
  );
}
