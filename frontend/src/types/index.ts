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
  marketRegime: 'BULL' | 'BEAR' | 'CHOP' | 'UNKNOWN';
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
  currentRegime: 'BULL' | 'BEAR' | 'CHOP' | 'UNKNOWN';
  overall: PerformanceSummary;
  byStrategy: Record<string, PerformanceSummary>;
  bySignalType: Record<string, PerformanceSummary>;
  bySymbol: Record<string, PerformanceSummary>;
  byExitReason: Record<string, PerformanceSummary>;
  byAlignmentBucket: Record<string, PerformanceSummary>;
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

// ==========================================================================
// Trade execution service (Plan 2b / Plan 3)
// ==========================================================================

export interface ExchangeAccount {
  id: number;
  exchange: string;
  environment: 'DEMO' | 'MAINNET';
  label: string | null;
  keyMask: string;
  autoTradeEnabled: boolean;
  killSwitch: boolean;
  riskPercent: number;
  defaultLeverage: number;
  maxConcurrentPositions: number;
  maxDailyLossPercent: number;
  signalAgeSeconds: number;
  positionMaxAgeHours: number;
  flipPersistenceTicks: number;
  createdAt: string;
  updatedAt: string;
}

export interface WalletSnapshot {
  equity: number;
  available: number;
  openPnl: number;
  todayRealized: number;
  positionsOpen: number;
}

export interface ExecutionPosition {
  id: number;
  accountId: number;
  signalId: string | null;
  symbol: string;
  direction: 'LONG' | 'SHORT';
  strategy: string | null;
  status: 'PENDING_PLACE' | 'OPEN' | 'CLOSING' | 'CLOSED' | 'FAILED' | 'CANCELLED';
  entryPrice: number | null;
  qty: number | null;
  leverage: number | null;
  stopPrice: number;
  targetPrice: number;
  dynamicStopPrice: number | null;
  trailHighestR: number;
  trailTriggeredAt: string | null;
  openedAt: string;
}

export interface ExecutionTrade {
  id: number;
  signalId: string | null;
  symbol: string;
  direction: 'LONG' | 'SHORT';
  strategy: string | null;
  status: ExecutionPosition['status'];
  entryPrice: number | null;
  exitPrice: number | null;
  qty: number | null;
  realizedPnlUsdt: number | null;
  realizedRMultiple: number | null;
  feesUsdt: number | null;
  exitReason: string | null;
  openedAt: string;
  closedAt: string | null;
}

export interface TradeHistoryPage {
  items: ExecutionTrade[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ExecutionEvent {
  id: number;
  eventType: string;
  signalId: string | null;
  executedTradeId: number | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface WhyView {
  tradeId: number;
  signalId: string | null;
  symbol: string;
  direction: 'LONG' | 'SHORT';
  strategy: string | null;
  openedAt: string;
  signalSnapshot: Record<string, unknown>;
}

export interface CreateAccountRequest {
  exchange: 'BYBIT';
  environment: 'DEMO' | 'MAINNET';
  apiKey: string;
  apiSecret: string;
  label?: string;
}

export interface UpdateAccountRequest {
  label?: string;
  autoTradeEnabled?: boolean;
  killSwitch?: boolean;
  riskPercent?: number;
  defaultLeverage?: number;
  maxConcurrentPositions?: number;
  maxDailyLossPercent?: number;
  signalAgeSeconds?: number;
  positionMaxAgeHours?: number;
  flipPersistenceTicks?: number;
}

// =============================================================================
// Signal config — runtime-tunable scoring parameters
// =============================================================================

export interface SignalWeights {
  technical: number;
  whale: number;
  orderBook: number;
  derivatives: number;
  sentiment: number;
  macro: number;
}

export interface TradeLevelsConfig {
  minRiskPct: number;
  minRr: number;
  atrStopMultiple: number;
  supportStopAtrBuffer: number;
}

export interface RsiConfig {
  oversoldExtreme: number;
  oversoldApproaching: number;
  overboughtApproaching: number;
  overboughtExtreme: number;
  scoreOversoldExtreme: number;
  scoreOversoldApproaching: number;
  scoreOverboughtApproaching: number;
  scoreOverboughtExtreme: number;
}

export interface MacdConfig {
  scoreBullish: number;
  scoreBearish: number;
}

export interface Sma200Config {
  scoreAbove: number;
  scoreBelow: number;
}

export interface BollingerConfig {
  lowerPosition: number;
  upperPosition: number;
  scoreLower: number;
  scoreUpper: number;
}

export interface VolumeConfirmationConfig {
  scoreDecreasing: number;
  scoreIncreasing: number;
}

export interface SupportResistanceConfig {
  lowerPosition: number;
  upperPosition: number;
  scoreNearSupport: number;
  scoreNearResistance: number;
}

export interface WhaleConfig {
  minSampleSize: number;
  amplifyThreshold: number;
  amplifyFactor: number;
}

export interface DerivativesFundingConfig {
  neutralThreshold: number;
  moderateThreshold: number;
  extremeThreshold: number;
  scoreModerate: number;
  scoreStrong: number;
  scoreExtreme: number;
}

export interface LongShortRatioConfig {
  extremelyCrowdedShortsPct: number;
  crowdedShortsPct: number;
  crowdedLongsPct: number;
  extremelyCrowdedLongsPct: number;
  scoreModerate: number;
  scoreExtreme: number;
}

export interface FearGreedConfig {
  extremeFearMax: number;
  fearMax: number;
  greedMin: number;
  extremeGreedMin: number;
  scoreModerate: number;
  scoreExtreme: number;
}

export interface NewsSentimentConfig {
  scoreMultiplier: number;
}

export interface OrderBookConfig {
  highLiquidationRatio: number;
  moderateLiquidationRatio: number;
  scoreHighVolatility: number;
}

export interface MacroBtcDominanceConfig {
  threshold: number;
  scoreBtcWhenHigh: number;
  scoreAltWhenHigh: number;
  scoreBtcWhenLow: number;
  scoreAltWhenLow: number;
}

export interface AlignmentConfig {
  minScoreForNonZero: number;
  contradictionScoreThreshold: number;
  contradictionPenaltyMultiplier: number;
  twoContradictionPenalty: number;
  oneContradictionPenalty: number;
  outputScale: number;
  minOutput: number;
  maxOutput: number;
}

export interface RegimeThresholdsConfig {
  strongBuyMinScore: number;
  buyMinScore: number;
  strongSellMaxScore: number;
  sellMaxScore: number;
  strongAlignmentMin: number;
  alignmentMin: number;
}

export interface BullOverridesConfig {
  strongSellMaxScore: number;
  sellMaxScore: number;
}

export interface BearOverridesConfig {
  strongBuyMinScore: number;
  buyMinScore: number;
}

export interface SignalLabelsConfig {
  chop: RegimeThresholdsConfig;
  bull: BullOverridesConfig;
  bear: BearOverridesConfig;
}

export interface TrailConfig {
  activationR: number;
  stepR: number;
  offsetR: number;
  widerOffsetActivationR: number;
  widerOffsetR: number;
}

export interface ExecutionSettings {
  alignmentFloor: number;
  symbolGateEnabled: boolean;
  symbolGateLookback: number;
  symbolGateThresholdR: number;
  symbolGateCacheTtlSec: number;
  confluenceTrendRequired: boolean;
  confluenceWindowMinutes: number;
  dailyPnlEquityCacheTtlSec: number;
  telegramEnabled: boolean;
  telegramChatId: string | null;
  telegramNotifiedEvents: string[];
  telegramNotifyOptions: boolean;
  // Read-only: server reports whether a bot token is stored, never the token.
  telegramConfigured: boolean;
  // Write-only: set to send a new token on PUT; server always returns null.
  telegramBotToken: string | null;
}

export interface TelegramTestResult {
  ok: boolean;
  error?: string;
}

export interface SignalConfig {
  weights: SignalWeights;
  tradeLevels: TradeLevelsConfig;
  rsi: RsiConfig;
  macd: MacdConfig;
  sma200: Sma200Config;
  bollinger: BollingerConfig;
  volumeConfirmation: VolumeConfirmationConfig;
  supportResistance: SupportResistanceConfig;
  whale: WhaleConfig;
  derivativesFunding: DerivativesFundingConfig;
  longShortRatio: LongShortRatioConfig;
  fearGreed: FearGreedConfig;
  newsSentiment: NewsSentimentConfig;
  orderBook: OrderBookConfig;
  macroBtcDominance: MacroBtcDominanceConfig;
  alignment: AlignmentConfig;
  signalLabels: SignalLabelsConfig;
  trail: TrailConfig;
}

export interface SignalConfigVersion {
  id: number;
  version: number;
  config: SignalConfig;
  description: string;
  parentVersionId: number | null;
  isActive: boolean;
  createdAt: string;
  createdBy: string;
}

// =============================================================================
// Options watchlist (Bybit strangle/straddle entry signals)
// =============================================================================

export interface OptionChainRow {
  time: string;
  symbol: string;
  underlying: string;
  expiry: string;
  strike: number;
  optionType: 'C' | 'P';
  bid: number | null;
  ask: number | null;
  mark: number | null;
  impliedVol: number | null;
  delta: number | null;
  gamma: number | null;
  theta: number | null;
  vega: number | null;
  openInterest: number | null;
  volume24h: number | null;
  underlyingPx: number | null;
}

export interface OptionOpportunity {
  id: number;
  detectedAt: string;
  underlying: string;
  expiry: string;
  strikeCall: number;
  strikePut: number;
  callSymbol: string;
  putSymbol: string;
  stranglePremium: number;
  impliedVolAtm: number | null;
  realizedVol7d: number | null;
  realizedVol14d: number | null;
  ivRvSpread: number | null;
  signalOverlay: number | null;
  confidence: number;
  metadata: Record<string, unknown> | null;
  realizedMovePct: number | null;
  outcomePnlPct: number | null;
  outcomeResolvedAt: string | null;
}

export interface RealizedVolReading {
  underlying: string;
  lookbackDays: number;
  annualizedPct: number;
}

export interface OptionLeg {
  time: string;
  symbol: string;
  optionType: 'C' | 'P';
  strike: number;
  bid: number | null;
  ask: number | null;
  mark: number | null;
  impliedVol: number | null;
  delta: number | null;
  gamma: number | null;
  theta: number | null;
  vega: number | null;
  openInterest: number | null;
  volume24h: number | null;
}

export interface EnrichedOptionOpportunity extends OptionOpportunity {
  callLeg: OptionLeg | null;
  putLeg: OptionLeg | null;
  netDelta: number | null;
  netGamma: number | null;
  netTheta: number | null;
  netVega: number | null;
  totalOpenInterest: number | null;
  totalVolume24h: number | null;
  // Live re-scored fields (recomputed by enricher at request time).
  // When live diverges from entry, the original "buy cheap vol" thesis has drifted.
  livePremium: number | null;
  liveImpliedVolAtm: number | null;
  liveIvRvSpread: number | null;
  liveSignalOverlay: number | null;
  liveConfidence: number | null;
  liveUnderlyingPx: number | null;
  isStale: boolean;
  staleReason: string | null;
}

export interface HitRateBucket {
  underlying: string;
  confidenceBucket: string;
  sampleSize: number;
  winRate: number | null;
  avgPnlPct: number | null;
}

// =============================================================================
// Backtest results
// =============================================================================

export interface BacktestRun {
  id: number;
  configVersionId: number;
  periodStart: string;
  periodEnd: string;
  tier: number;
  tradeCount: number;
  winCount: number;
  lossCount: number;
  totalR: number;
  avgR: number;
  avgWinnerR: number | null;
  avgLoserR: number | null;
  originalTradeCount: number;
  originalTotalR: number;
  metadata: Record<string, unknown> | null;
  createdAt: string;
  durationMs: number;
}

export interface BacktestTrade {
  id: number;
  backtestRunId: number;
  outcomeSignalId: string;
  outcomeFiredAt: string;
  symbol: string;
  direction: string;
  originalSignal: string;
  originalAlignment: number;
  backtestSignal: string;
  backtestAlignment: number;
  realizedRMultiple: number | null;
  backtestWouldIssue: boolean;
  contributedR: number;
}

export interface BacktestRunDetail extends BacktestRun {
  trades: BacktestTrade[];
}
