import { Link, useLocation } from 'react-router-dom';
import { Activity, Radio, Waves, BarChart3, Settings, Grid3X3, Columns, Wallet, Filter, Zap, FlaskConical, SlidersHorizontal, TrendingUp } from 'lucide-react';

export function Header() {
  const location = useLocation();

  const navLink = (to: string, label: string, Icon: any) => {
    const isActive = to === '/' ? location.pathname === '/' : location.pathname.startsWith(to);
    return (
      <Link
        to={to}
        className={`flex items-center gap-1.5 text-sm transition-colors ${
          isActive
            ? 'text-accent font-medium'
            : 'text-text-secondary hover:text-accent'
        }`}
      >
        <Icon className="h-4 w-4" />
        <span>{label}</span>
      </Link>
    );
  };

  return (
    <header className="sticky top-0 z-50 border-b border-surface-border bg-background/80 backdrop-blur-md">
      <div className="container mx-auto px-4 max-w-[1600px]">
        <div className="flex items-center justify-between h-16">
          <Link to="/" className="flex items-center gap-3 group">
            <div className="relative">
              <Activity className="h-7 w-7 text-accent" />
              <div className="absolute -top-0.5 -right-0.5 w-2.5 h-2.5 bg-gain rounded-full pulse-dot" />
            </div>
            <div>
              <h1 className="text-xl font-bold tracking-tight">
                <span className="text-accent">Crypto</span>
                <span className="text-text-primary">Radar</span>
              </h1>
            </div>
          </Link>
          <nav className="flex items-center gap-4">
            {navLink('/signals', 'Signals', Zap)}
            {navLink('/', 'Dashboard', BarChart3)}
            {navLink('/screener', 'Screener', Filter)}
            {navLink('/whales', 'Whales', Waves)}
            {navLink('/derivatives', 'Leverage', BarChart3)}
            {navLink('/analytics', 'Analytics', Grid3X3)}
            {navLink('/compare', 'Compare', Columns)}
            {navLink('/portfolio', 'Portfolio', Wallet)}
            {navLink('/backtest', 'Backtest', FlaskConical)}
            {navLink('/options', 'Options', TrendingUp)}
            {navLink('/signal-config', 'Engine', SlidersHorizontal)}
            {navLink('/config', 'Settings', Settings)}
            <div className="flex items-center gap-2 text-sm text-text-secondary">
              <Radio className="h-4 w-4 text-gain animate-pulse" />
              <span>Live</span>
            </div>
          </nav>
        </div>
      </div>
    </header>
  );
}
