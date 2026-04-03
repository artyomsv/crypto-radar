export interface PriceData {
  symbol: string;
  name: string;
  price: number;
  priceChange24h: number;
  priceChangePct24h: number;
  volume24h: number;
  marketCap: number;
  sparkline: number[];
}

export interface DashboardData {
  prices: PriceData[];
  marketOverview: MarketOverview | null;
  latestNews: NewsArticle[];
  timestamp: string;
}

export interface Candle {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface TechnicalIndicators {
  symbol: string;
  timestamp: string;
  rsi14: number | null;
  macdLine: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
  bollingerUpper: number | null;
  bollingerMiddle: number | null;
  bollingerLower: number | null;
  ema12: number | null;
  ema26: number | null;
  sma20: number | null;
  sma50: number | null;
  sma200: number | null;
  atr14: number | null;
}

export interface MarketAnalysis {
  symbol: string;
  timestamp: string;
  overallScore: number;
  trendDirection: string;
  trendStrength: number;
  technicalIndicators: TechnicalIndicators;
  sentimentScore: number;
  volumeTrend: string;
  supportLevel: number;
  resistanceLevel: number;
  signals: string[];
}

export interface MarketOverview {
  timestamp: string;
  marketSentiment: string;
  technicalScore: number;
  technicalScoreLabel: string;
  fearGreedIndex: number;
  fearGreedLabel: string;
  bullishCount: number;
  bearishCount: number;
  neutralCount: number;
  topGainer: string;
  topLoser: string;
  analyses: MarketAnalysis[];
}

export interface NewsArticle {
  id: number;
  title: string;
  body: string;
  url: string;
  source: string;
  imageUrl: string;
  publishedAt: string;
  sentimentScore: number;
  sentimentLabel: string;
  relatedSymbols: string[];
}

export interface CryptoDetail {
  priceData: PriceData;
  candles: Candle[];
  technicalIndicators: TechnicalIndicators;
  analysis: MarketAnalysis;
  news: NewsArticle[];
  sentimentHistory: SentimentDay[];
}

export interface SentimentDay {
  date: string;
  avgSentiment: number;
  positiveCount: number;
  negativeCount: number;
  neutralCount: number;
  totalArticles: number;
}

export const SYMBOL_NAMES: Record<string, string> = {
  BTCUSDT: 'Bitcoin',
  ETHUSDT: 'Ethereum',
  BNBUSDT: 'BNB',
  SOLUSDT: 'Solana',
  XRPUSDT: 'XRP',
  ADAUSDT: 'Cardano',
  AVAXUSDT: 'Avalanche',
  DOTUSDT: 'Polkadot',
  LINKUSDT: 'Chainlink',
  DOGEUSDT: 'Dogecoin',
};

export const SYMBOL_ICONS: Record<string, string> = {
  BTCUSDT: '\u20bf',
  ETHUSDT: '\u039e',
  BNBUSDT: '\u25c6',
  SOLUSDT: '\u25ce',
  XRPUSDT: '\u2715',
  ADAUSDT: '\u20b3',
  AVAXUSDT: '\u25b2',
  DOTUSDT: '\u25cf',
  LINKUSDT: '\u2b21',
  DOGEUSDT: '\u00d0',
};

export interface WhaleTransaction {
  time: string;
  symbol: string;
  price: number;
  quantity: number;
  valueUsd: number;
  side: 'BUY' | 'SELL';
  source: string;
  tradeId?: string;
  fromLabel?: string;
  toLabel?: string;
  blockchain?: string;
  txHash?: string;
}

export interface WhaleFlowSummary {
  bucket: string;
  symbol: string;
  buyCount: number;
  sellCount: number;
  buyVolumeUsd: number;
  sellVolumeUsd: number;
  netFlowUsd: number;
  avgTradeSizeUsd: number;
  largestTradeUsd: number;
  whalePressure: number;
}

export interface WhaleAnalytics {
  symbol: string;
  timestamp: string;
  whaleActivityScore: number;
  whalePressure: number;
  pressureLabel: string;
  buyVolumeUsd1h: number;
  sellVolumeUsd1h: number;
  netFlowUsd1h: number;
  tradeCount1h: number;
  largestTradeUsd: number;
  avgTradeSizeUsd: number;
  buyVolumeUsd24h: number;
  sellVolumeUsd24h: number;
  netFlowUsd24h: number;
  tradeCount24h: number;
}

export interface WhaleMarketOverview {
  timestamp: string;
  totalWhaleVolume24h: number;
  totalBuyVolume24h: number;
  totalSellVolume24h: number;
  overallPressure: number;
  overallPressureLabel: string;
  mostBought: string;
  mostSold: string;
  activeSymbolCount: number;
  totalTradeCount24h: number;
  symbolAnalytics: WhaleAnalytics[];
}
