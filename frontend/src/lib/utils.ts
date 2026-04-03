import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatPrice(price: number): string {
  if (price >= 1000) return price.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (price >= 1) return price.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4 });
  return price.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 4, maximumFractionDigits: 6 });
}

export function formatLargeNumber(num: number): string {
  if (num >= 1e12) return `$${(num / 1e12).toFixed(2)}T`;
  if (num >= 1e9) return `$${(num / 1e9).toFixed(2)}B`;
  if (num >= 1e6) return `$${(num / 1e6).toFixed(2)}M`;
  if (num >= 1e3) return `$${(num / 1e3).toFixed(2)}K`;
  return `$${num.toFixed(2)}`;
}

export function formatPercent(pct: number): string {
  const sign = pct >= 0 ? '+' : '';
  return `${sign}${pct.toFixed(2)}%`;
}

export function formatTimeAgo(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

export function getScoreColor(score: number): string {
  if (score >= 50) return 'text-gain';
  if (score >= 20) return 'text-gain-light';
  if (score >= -20) return 'text-text-secondary';
  if (score >= -50) return 'text-loss-light';
  return 'text-loss';
}

export function getTrendBadgeColor(trend: string): string {
  switch (trend) {
    case 'STRONG_BULLISH': return 'bg-gain/20 text-gain border-gain/30';
    case 'BULLISH': return 'bg-gain/10 text-gain-light border-gain/20';
    case 'NEUTRAL': return 'bg-muted/20 text-text-secondary border-muted/30';
    case 'BEARISH': return 'bg-loss/10 text-loss-light border-loss/20';
    case 'STRONG_BEARISH': return 'bg-loss/20 text-loss border-loss/30';
    default: return 'bg-muted/20 text-text-secondary border-muted/30';
  }
}

export const API_BASE = '';
