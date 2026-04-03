import { useDashboardData } from '@/hooks/useCryptoData';
import { PriceCard } from './PriceCard';
import { MarketOverviewPanel } from './MarketOverviewPanel';
import { NewsFeed } from './NewsFeed';
import { Loader2 } from 'lucide-react';

export function Dashboard() {
  const { data, loading, error, connected, priceFlash } = useDashboardData();

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="flex flex-col items-center gap-4">
          <Loader2 className="h-8 w-8 text-accent animate-spin" />
          <p className="text-text-secondary text-sm">Loading market data...</p>
        </div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="glass-card p-8 text-center max-w-md">
          <p className="text-loss text-lg font-medium mb-2">Connection Error</p>
          <p className="text-text-secondary text-sm">
            {error || 'Unable to fetch dashboard data. Make sure the API gateway is running on port 8080.'}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Market Overview */}
      {data.marketOverview && (
        <MarketOverviewPanel overview={data.marketOverview} />
      )}

      {/* Price Cards Grid */}
      <section>
        <div className="flex items-center gap-3 mb-4">
          <h2 className="text-lg font-semibold text-text-primary">Top Cryptocurrencies</h2>
          <div className="flex items-center gap-1.5">
            <span className={`live-dot ${connected ? 'connected' : 'disconnected'}`} />
            <span className={`text-xs ${connected ? 'text-gain' : 'text-loss'}`}>
              {connected ? 'Live' : 'Reconnecting...'}
            </span>
          </div>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4">
          {[...data.prices]
            .sort((a, b) => (b.volume24h ?? 0) - (a.volume24h ?? 0))
            .map((price) => (
              <PriceCard key={price.symbol} data={price} flash={priceFlash[price.symbol]} />
            ))}
        </div>
      </section>

      {/* News Feed */}
      {data.latestNews.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold text-text-primary mb-4">Latest News</h2>
          <NewsFeed articles={data.latestNews} />
        </section>
      )}
    </div>
  );
}
