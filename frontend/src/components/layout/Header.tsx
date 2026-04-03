import { Link } from 'react-router-dom';
import { Activity, Radio } from 'lucide-react';

export function Header() {
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
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2 text-sm text-text-secondary">
              <Radio className="h-4 w-4 text-gain animate-pulse" />
              <span>Live</span>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}
