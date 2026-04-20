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
  XRPUSDT: 'XRP',
  BNBUSDT: 'BNB',
  SOLUSDT: 'Solana',
  TRXUSDT: 'TRON',
  DOGEUSDT: 'Dogecoin',
  BCHUSDT: 'Bitcoin Cash',
  ADAUSDT: 'Cardano',
  LINKUSDT: 'Chainlink',
  XMRUSDT: 'Monero',
  XLMUSDT: 'Stellar',
  LTCUSDT: 'Litecoin',
  ZECUSDT: 'Zcash',
};

export const SYMBOL_ICONS: Record<string, string> = {
  BTCUSDT: '\u20bf',
  ETHUSDT: '\u039e',
  XRPUSDT: '\u2715',
  BNBUSDT: '\u25c6',
  SOLUSDT: '\u25ce',
  TRXUSDT: '\u25c8',
  DOGEUSDT: '\u00d0',
  BCHUSDT: '\u0e3f',
  ADAUSDT: '\u20b3',
  LINKUSDT: '\u2b21',
  XMRUSDT: '\u0271',
  XLMUSDT: '\u2726',
  LTCUSDT: '\u0141',
  ZECUSDT: '\u24e9',
};

export interface WhaleTransaction {
  time: string;
  symbol: string;
  price: number;
  quantity: number;
  valueUsd: number;
  side: 'BUY' | 'SELL' | 'TRANSFER';
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

export interface WhaleDistributionSide {
  buyVolume: number;
  sellVolume: number;
  buyCount: number;
  sellCount: number;
}

export interface WhaleDistribution {
  window: string;
  exchange: WhaleDistributionSide;
  onchain: WhaleDistributionSide;
}

export interface FundingRate {
  symbol: string;
  fundingRate: number;
  fundingTime: string;
  nextFundingTime: string;
  markPrice: number;
  indexPrice: number;
}

export interface SymbolDerivatives {
  symbol: string;
  fundingRate: number;
  fundingRateAnnualized: number;
  openInterestUsd: number;
  longShortRatio: number;
  longPct: number;
  shortPct: number;
  liquidations24hUsd: number;
  sentiment: string;
}

export interface DerivativesOverview {
  timestamp: string;
  totalOpenInterestUsd: number;
  avgFundingRate: number;
  fundingRateLabel: string;
  totalLiquidations24h: number;
  longLiquidations24h: number;
  shortLiquidations24h: number;
  marketLongPct: number;
  marketShortPct: number;
  symbolData: SymbolDerivatives[];
}

export interface LiquidationEvent {
  symbol: string;
  side: string;
  price: number;
  quantity: number;
  valueUsd: number;
  time: string;
}

export interface PriceAlert {
  id: number;
  symbol: string;
  condition: 'ABOVE' | 'BELOW';
  targetPrice: number;
  isActive: boolean;
  isTriggered: boolean;
  triggeredAt: string | null;
  createdAt: string;
  note: string | null;
}

export interface TriggeredAlert {
  id: number;
  symbol: string;
  condition: 'ABOVE' | 'BELOW';
  targetPrice: number;
  currentPrice: number;
  note: string | null;
  triggeredAt: string;
}

export interface CorrelationMatrix {
  timestamp: string;
  interval: string;
  days: number;
  matrix: Record<string, Record<string, number>>;
}

export interface VolatilityMetric {
  symbol: string;
  atrPct: number | null;
  bollingerWidth: number | null;
  range24hPct: number | null;
  volatilityRank: number;
}

export interface PortfolioPosition {
  id: number;
  symbol: string;
  entryPrice: number;
  quantity: number;
  side: string;
  note?: string;
  openedAt: string;
  isOpen: boolean;
  closedAt?: string;
  closePrice?: number;
}

export interface MacroOverview {
  btcDominance: number;
  ethDominance: number;
  totalMarketCapUsd: number;
  usdtMarketCap: number;
  usdcMarketCap: number;
  totalStablecoinCap: number;
  defiTvlUsd: number;
  timestamp: string;
}

export interface DimensionScore {
  name: string;
  score: number;
  weight: number;
  reasons: string[];
}

export interface TradingSignal {
  symbol: string;
  timestamp: string;
  signal: string;
  overallScore: number;
  alignment: number;
  dimensions: DimensionScore[];
  suggestedEntry: number | null;
  suggestedStopLoss: number | null;
  suggestedTakeProfit: number | null;
  riskRewardRatio: number | null;
  alertLevel: string;
  previousSignal: string;
  aiAnalysis: string | null;
  aiAnalysisTimestamp: string | null;
}

export interface SignalOverview {
  timestamp: string;
  strongBuyCount: number;
  buyCount: number;
  neutralCount: number;
  sellCount: number;
  strongSellCount: number;
  marketBias: string;
  topOpportunity: TradingSignal | null;
  signals: TradingSignal[];
}

export interface PerformanceSummary {
  total: number;
  pending: number;
  hitTarget: number;
  hitStop: number;
  expired: number;
  winRate: number;
  avgRMultiple: number;
  totalRMultiple: number;
  bestRMultiple: number;
  worstRMultiple: number;
  profitFactor: number;
  avgMaxFavorablePct: number;
  avgMaxAdversePct: number;
}

export interface PerformanceReport {
  from: string;
  to: string;
  periodDays: number;
  overall: PerformanceSummary;
  byStrategy: Record<string, PerformanceSummary>;
  bySignalType: Record<string, PerformanceSummary>;
  bySymbol: Record<string, PerformanceSummary>;
}

export interface SignalOutcomeView {
  signalId: string;
  symbol: string;
  strategy: string;
  signalType: string;
  direction: 'LONG' | 'SHORT';
  firedAt: string;
  entryPrice: number;
  stopPrice: number;
  targetPrice: number;
  riskRewardRatio: number;
  alignment: number;
  status: 'PENDING' | 'HIT_TARGET' | 'HIT_STOP' | 'EXPIRED';
  closedAt: string | null;
  closedPrice: number | null;
  realizedPnlPct: number | null;
  realizedRMultiple: number | null;
  maxFavorablePct: number;
  maxAdversePct: number;
  aiAnalysis: string | null;
  // Trailing-stop state (PR2)
  dynamicStopPrice: number | null;
  trailTriggeredAt: string | null;
  trailHighestR: number | null;
  finalExitReason: 'INITIAL_STOP' | 'TRAIL_STOP' | 'TARGET' | 'EXPIRED' | null;
  // Timing (PR5)
  timeToMfeSeconds: number | null;
  timeToMaeSeconds: number | null;
}

export interface PriceLevelData {
  price: number;
  quantity: number;
  totalUsd: number;
  exchangeCount: number;
}

export interface OrderBookDepth {
  symbol: string;
  timestamp: string;
  bids: PriceLevelData[];
  asks: PriceLevelData[];
  bestBid: number;
  bestAsk: number;
  spread: number;
  spreadPct: number;
  totalBidVolume: number;
  totalAskVolume: number;
  bidAskImbalance: number;
}
