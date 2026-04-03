import { useState, useEffect } from 'react';
import { ExternalLink, Clock, TrendingUp, TrendingDown, Minus, X, Loader2 } from 'lucide-react';
import type { NewsArticle } from '@/types';
import { formatTimeAgo } from '@/lib/utils';
import { api } from '@/lib/api';

interface NewsFeedProps {
  articles: NewsArticle[];
}

export function NewsFeed({ articles }: NewsFeedProps) {
  const [selectedArticle, setSelectedArticle] = useState<NewsArticle | null>(null);

  return (
    <>
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {articles.slice(0, 12).map((article) => (
          <NewsCard key={article.id} article={article} onClick={() => setSelectedArticle(article)} />
        ))}
      </div>

      {selectedArticle && (
        <ArticleDialog article={selectedArticle} onClose={() => setSelectedArticle(null)} />
      )}
    </>
  );
}

function ArticleDialog({ article, onClose }: { article: NewsArticle; onClose: () => void }) {
  const [fullContent, setFullContent] = useState<{ body: string; imageUrl: string; images: string[] } | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!article.url) {
      setLoading(false);
      return;
    }
    setLoading(true);
    api.fetchArticle(article.url).then(data => {
      if (data && data.body) {
        setFullContent({ body: data.body, imageUrl: data.imageUrl || '', images: data.images || [] });
      }
      setLoading(false);
    });
  }, [article.url]);

  const sentimentColor = article.sentimentScore > 0.1
    ? 'text-gain'
    : article.sentimentScore < -0.1
      ? 'text-loss'
      : 'text-text-secondary';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface border border-surface-border rounded-xl max-w-3xl w-full mx-4 max-h-[85vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <div className="sticky top-0 bg-surface/95 backdrop-blur-sm border-b border-surface-border p-4 flex items-center justify-between z-10">
          <h2 className="text-base font-semibold text-text-primary pr-4 line-clamp-2">{article.title}</h2>
          <div className="flex items-center gap-2 shrink-0">
            <a href={article.url} target="_blank" rel="noopener noreferrer"
               className="flex items-center gap-1 px-2 py-1 text-xs text-accent hover:bg-accent/10 rounded transition-colors">
              <ExternalLink className="h-4 w-4" />
              Source
            </a>
            <button onClick={onClose} className="text-text-secondary hover:text-text-primary p-1">
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="h-8 w-8 text-accent animate-spin" />
          </div>
        ) : (
        <div className="p-5 space-y-4">
          {fullContent?.imageUrl && (
            <img src={fullContent.imageUrl} alt="" className="w-full rounded-lg max-h-72 object-cover" />
          )}
          <div className="flex items-center gap-3 text-sm text-text-secondary">
            <span className="font-medium text-text-primary">{article.source}</span>
            <span>{formatTimeAgo(article.publishedAt)}</span>
            <span className={sentimentColor}>
              {article.sentimentLabel || 'Neutral'}
            </span>
          </div>
          <div className="text-sm text-text-primary leading-relaxed whitespace-pre-line">
            {fullContent?.body || article.body}
          </div>
          {article.relatedSymbols?.length > 0 && (
            <div className="flex flex-wrap gap-1 pt-2 border-t border-surface-border">
              {article.relatedSymbols.map(sym => (
                <span key={sym} className="text-xs px-2 py-0.5 rounded bg-accent/10 text-accent border border-accent/20">{sym}</span>
              ))}
            </div>
          )}
        </div>
        )}
      </div>
    </div>
  );
}

function NewsCard({ article, onClick }: { article: NewsArticle; onClick: () => void }) {
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
    <div
      onClick={onClick}
      className="glass-card p-4 hover:bg-surface-light/80 transition-all duration-200 group flex flex-col cursor-pointer"
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
    </div>
  );
}
