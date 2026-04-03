# Whale Tracker Service — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Build a whale-service microservice that tracks large crypto transactions in real-time, provides analytics, and powers a high-end trading dashboard.

**Architecture:** New Quarkus microservice (port 8084) with 3 data tiers: (1) Binance WebSocket trade streams for real-time large trade detection — no API key needed, (2) Whale Alert REST API for cross-chain whale transactions, (3) Analytics engine computing whale flow, buy/sell pressure, and correlation with price. Data stored in TimescaleDB, events via Redis pub/sub, live updates via WebSocket.

**Tech Stack:** Quarkus 3.17.5, Java 21, TimescaleDB, Redis, Binance WebSocket Streams, Whale Alert API (optional), React + TradingView charts.

---

## Phase 1: Whale Service Core + Binance Large Trade Detection

### Data Source: Binance WebSocket Trade Streams
- URL: `wss://stream.binance.com:9443/stream?streams=btcusdt@aggTrade/ethusdt@aggTrade/...`
- Each event: `{symbol, price, quantity, isBuyerMaker, timestamp}`
- Filter: trades > $50K (configurable threshold) = "whale trades"
- No API key required, unlimited, real-time

### Database Tables (TimescaleDB)
- `whale_transactions` — individual whale trades (hypertable)
- `whale_flow_summary` — aggregated net flow per symbol per time bucket
- `whale_wallets` — labeled whale addresses (Phase 2)

### Service Structure
```
services/whale-service/
├── pom.xml
├── Dockerfile
├── src/main/resources/application.properties
└── src/main/java/com/cryptoradar/whale/
    ├── model/           WhaleTransaction, WhaleFlowSummary, WhaleAnalytics
    ├── provider/         WhaleDataProvider interface
    ├── provider/binance/ BinanceTradeStreamProvider (WebSocket client)
    ├── provider/alert/   WhaleAlertProvider (REST, Phase 2)
    ├── service/          WhaleAnalyticsService, WhaleFlowService
    ├── event/            RedisEventPublisher
    ├── resource/         WhaleResource (REST endpoints)
    └── scheduler/        WhaleScheduler (analytics recomputation)
```

### REST Endpoints
- GET /api/whales/transactions?symbol=&limit=50 — recent whale trades
- GET /api/whales/flow/{symbol}?window=1h — net inflow/outflow
- GET /api/whales/analytics — aggregated whale analytics for all symbols
- GET /api/whales/summary — whale activity summary (for dashboard cards)
- GET /api/whales/providers — list of active data providers

### Redis Channel: `crypto:whales`
### WebSocket Message: `{"type":"whales","data":{...}}`

## Phase 2: Whale Alert Integration

### Data Source: Whale Alert API (free tier, 10 req/min)
- Endpoint: `https://api.whale-alert.io/v1/transactions`
- Covers: BTC, ETH, XRP, SOL, ADA, DOGE + exchanges
- Adds: cross-chain transfers, exchange in/out flows, labeled wallets

## Phase 3: Analytics & Dashboard

### Analytics Engine
- Net whale flow per symbol (buying pressure vs selling pressure)
- Whale activity score (0-100) per symbol
- Whale vs retail ratio
- Large trade frequency analysis
- Exchange inflow/outflow tracking (whale deposits = potential sell)
- Correlation: whale activity vs price movement
- Whale sentiment: are whales accumulating or distributing?

### Frontend Dashboard Components
- WhaleActivityFeed — real-time scrolling feed of large trades
- WhaleFlowChart — buy vs sell pressure bar chart per symbol
- WhaleHeatmap — activity heatmap across all symbols
- WhaleIndicator on crypto cards — whale activity badge
- WhaleAnalytics in detail view — flow charts, whale trades for specific coin

## Integration Points
- api-gateway: new Redis subscription for `crypto:whales`, broadcast via WebSocket
- Frontend useWebSocket: handle `whales` message type
- Docker Compose: new whale-service definition
- Nginx: proxy /api/whales/* if needed (handled by gateway proxy)
