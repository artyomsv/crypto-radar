import { ExternalLink, Clock, TrendingUp, TrendingDown, Minus } from 'lucide-react';
import type { NewsArticle } from '@/types';
import { formatTimeAgo } from '@/lib/utils';

interface NewsFeedProps {
  articles: NewsArticle[];
}

export function NewsFeed({ articles }: NewsFeedProps) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
      {articles.slice(0, 12).map((article) => (
        <NewsCard key={article.id} article={article} />
      ))}
    </div>
  );
}

function NewsCard({ article }: { article: NewsArticle }) {
  const sentimentIcon = article.sentimentScore > 0.1
    ? <TrendingUp className="h-3 w-3 text-gain" />
    : article.sentimentScore < -0.1
      ? <TrendingDown className="h-3 w-3 text-loss" />
      : <Minus className="h-3 w-3 text-text-secondary" />;

  const sentimentColor = article.sentimentScore > 0.1
    ? 'text-gain'
    : article.sentimentScore < -0.1
      ? 'text-loss'
      : 'text-text-secondary';

  return (
    <a
      href={article.url}
      target="_blank"
      rel="noopener noreferrer"
      className="glass-card p-4 hover:bg-surface-light/80 transition-all duration-200 group flex flex-col"
    >
      <div className="flex items-start justify-between gap-2 mb-2">
        <h3 className="text-sm font-medium text-text-primary line-clamp-2 group-hover:text-accent transition-colors">
          {article.title}
        </h3>
        <ExternalLink className="h-3.5 w-3.5 text-text-secondary shrink-0 mt-0.5 opacity-0 group-hover:opacity-100 transition-opacity" />
      </div>

      {article.body && (
        <p className="text-xs text-text-secondary line-clamp-2 mb-3 flex-1">
          {article.body}
        </p>
      )}

      <div className="flex items-center justify-between text-xs mt-auto">
        <div className="flex items-center gap-3">
          <span className="text-text-secondary">{article.source}</span>
          <span className="flex items-center gap-1 text-text-secondary">
            <Clock className="h-3 w-3" />
            {formatTimeAgo(article.publishedAt)}
          </span>
        </div>
        <div className="flex items-center gap-1">
          {sentimentIcon}
          <span className={`font-medium ${sentimentColor}`}>
            {article.sentimentLabel || (article.sentimentScore > 0 ? 'Positive' : article.sentimentScore < 0 ? 'Negative' : 'Neutral')}
          </span>
        </div>
      </div>

      {article.relatedSymbols && article.relatedSymbols.length > 0 && (
        <div className="flex flex-wrap gap-1 mt-2">
          {article.relatedSymbols.map((sym) => (
            <span
              key={sym}
              className="text-[10px] px-1.5 py-0.5 rounded bg-accent/10 text-accent border border-accent/20"
            >
              {sym.replace('USDT', '')}
            </span>
          ))}
        </div>
      )}
    </a>
  );
}
