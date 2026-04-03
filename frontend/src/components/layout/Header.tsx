import { useState, useRef, useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Activity, Radio, Waves, BarChart3, Settings, Grid3X3, Columns, Wallet, Filter, ChevronDown } from 'lucide-react';

export function Header() {
  const location = useLocation();
  const [moreOpen, setMoreOpen] = useState(false);
  const moreRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (moreRef.current && !moreRef.current.contains(e.target as Node)) {
        setMoreOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  // Close dropdown on navigation
  useEffect(() => {
    setMoreOpen(false);
  }, [location.pathname]);

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

  const dropdownLink = (to: string, label: string, Icon: any) => {
    const isActive = to === '/' ? location.pathname === '/' : location.pathname.startsWith(to);
    return (
      <Link
        to={to}
        className={`flex items-center gap-2 px-3 py-2 text-sm transition-colors rounded hover:bg-surface-light ${
          isActive ? 'text-accent font-medium' : 'text-text-secondary hover:text-accent'
        }`}
      >
        <Icon className="h-4 w-4" />
        <span>{label}</span>
      </Link>
    );
  };

  const morePages = ['/compare', '/portfolio', '/whales', '/derivatives'];
  const isMoreActive = morePages.some(p => location.pathname.startsWith(p));

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
          <nav className="flex items-center gap-5">
            {navLink('/', 'Dashboard', BarChart3)}
            {navLink('/screener', 'Screener', Filter)}
            {navLink('/analytics', 'Analytics', Grid3X3)}

            {/* More dropdown for secondary pages */}
            <div className="relative" ref={moreRef}>
              <button
                onClick={() => setMoreOpen(!moreOpen)}
                className={`flex items-center gap-1 text-sm transition-colors ${
                  isMoreActive ? 'text-accent font-medium' : 'text-text-secondary hover:text-accent'
                }`}
              >
                <span>More</span>
                <ChevronDown className={`h-3.5 w-3.5 transition-transform ${moreOpen ? 'rotate-180' : ''}`} />
              </button>
              {moreOpen && (
                <div className="absolute right-0 top-full mt-2 w-44 bg-surface border border-surface-border rounded-lg shadow-lg py-1 z-50">
                  {dropdownLink('/compare', 'Compare', Columns)}
                  {dropdownLink('/portfolio', 'Portfolio', Wallet)}
                  {dropdownLink('/whales', 'Whales', Waves)}
                  {dropdownLink('/derivatives', 'Leverage', BarChart3)}
                </div>
              )}
            </div>

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
