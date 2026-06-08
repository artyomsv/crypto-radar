# Exchange Listings & Delistings

> Adding a token to a major exchange is one of the few near-deterministic short-term price catalysts in crypto. Removing it is one of the most operationally dangerous events for a systematic trading system that doesn't notice.

## Definition

### Listings — the "Coinbase Effect"

When a major exchange (Binance, Coinbase, Bybit, OKX, Kraken, Upbit) announces support for a new asset, that asset typically pumps in the minutes-to-hours window between announcement and active trading. The pattern is so consistent it has a name — the **Coinbase Effect** — and an academic literature.

Empirical findings (Coinbase pre-2021 era, multiple studies):

- Median +29% return in the 5-day window around a Coinbase listing announcement.
- Effect is strongest for listings on **Coinbase retail-facing** product (Coinbase.com), weaker for Coinbase Pro / Coinbase Exchange (institutional).
- The effect has decayed post-2021 — increased market efficiency, more pre-announcement leakage, more exchanges reducing the marginal-customer impact.
- Survives transaction costs only when the trader is positioned **before** the public announcement.

Sub-categories of listing pump:

1. **Spot listing on Binance.** Typically the biggest pump (largest user base globally).
2. **Perpetual listing on Bybit / Binance Futures.** Smaller pump, but adds leverage capacity → second-order positioning.
3. **Korean exchange listing (Upbit, Bithumb).** Outsized effect on Korea-favored assets (specific narratives, retail-popular tokens).
4. **US-tier listing post-regulatory clarity.** Examples: Coinbase listing assets after Fox/Howey clarification; usually large pumps with high holding-period variance.

### Delistings — the destructive side

Exchanges delist for multiple reasons:

- **Regulatory pressure** (Binance and Coinbase delisted XMR — Monero — in early 2024 after FATF travel-rule pressure).
- **Inadequate liquidity** ("low-volume" delistings every quarter, mostly small-caps).
- **Project deterioration** (team disappears, project becomes non-functional).
- **Voluntary issuer withdrawal** (rare; typically post-acquisition).

The pump on listing is well-documented; the dump on delisting is similarly mechanical:

- **Spot delisting**: large drop in the days leading up to and following the announcement. Affected holders rush exits while the remaining venue list shrinks.
- **Perpetual delisting**: open positions are force-closed at the index price at delisting cutoff. Holders of open positions get involuntarily liquidated.
- **API consequence**: kline endpoints often continue serving frozen historical data after the symbol becomes untradable. Systematic systems that rely on price availability without trading status check will keep "trading" against a market that no longer exists.

### The XMR case (relevant to projectr-x)

Binance delisted Monero (XMR) on 2024-02-20. The Binance kline endpoint for `XMRUSDT` continued to serve historical candles after delisting — apparently frozen at the last actively-traded price. Any system polling `klines?symbol=XMRUSDT` saw what looked like a live market.

projectr-x was affected: `market-data-service` continued ingesting these frozen klines; `signal-service` continued generating signals against them; `OutcomeEvaluator` could never resolve open outcomes because the prices never moved. The fix was a manual symbol removal (XMRUSDT dropped from the trading universe). The structural fix — automated delisting detection — is tracked in `techdebt/2-2-silent-delisting-detection-gap.md` and remains open.

## When it works

- **Pre-positioning before announcement.** If you have a credible signal of an imminent listing (analyst access, on-chain wallet-prep movements, partnership leak), the pre-announcement entry has historically been profitable. Most retail can't get this signal at scale; institutional traders do.
- **Fade the listing pump.** Once the asset is live and the announcement has been digested (often within 30–60 minutes), the elevated price tends to mean-revert. Shorting a fresh listing on a small perp venue is one of the cleaner late-cycle trades.
- **Avoid a known-delisting-soon asset.** Filtering the trade universe to exclude assets with formal delisting timelines is the cheapest risk control available.

## When it fails

- **Late-comer chasing.** The pump is largely done within minutes. Retail traders who buy "after the news drops" generally fund the pre-positioned entrants' exits.
- **No leakage in modern markets.** Big exchanges have tightened operational security; the pre-announcement signal that worked in 2017–2019 is much rarer in 2024–2025.
- **Fake-listing spoofs.** Lower-quality "exchanges" announce listings to pump the token, then never actually open trading. Most prominent in 2022 token-launch cycle.
- **Delisting recovery rallies.** Occasionally a delisting on one exchange (regulatory) coincides with a price floor as long-term holders take it personally. The 2024 XMR community response (rapid migration to atomic-swap DEXes) softened the dump.
- **Frozen-kline contamination of backtests.** Backtests run over historical universes that included now-delisted assets generate apparent edge that disappears in live — the "winners" sample is biased by the delisted-from-the-data losers.

## What we do today (in projectr-x)

### Universe

Currently 13 USDT-perpetual pairs on Bybit V5: `BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, ADAUSDT, DOGEUSDT, AVAXUSDT, LINKUSDT, DOTUSDT, LTCUSDT, ATOMUSDT, NEARUSDT`. XMRUSDT was dropped after the Binance delisting incident.

### Universe maintenance

Manual today. The list lives in service configuration (`market-data-service` config + `signal-service` `application.yml` symbol arrays). Adding or removing a symbol requires a code change and redeploy.

### Delisting detection gap (open techdebt)

`techdebt/2-2-silent-delisting-detection-gap.md` documents the XMR-style failure mode. The proposed fix:

1. **Liveness check** in `market-data-service`: for each tracked symbol, periodically verify Bybit's `/v5/market/instruments-info` shows `status="Trading"` (not "Closed" or "Settling"). Compare to the cached symbol list.
2. **Stale-kline check**: alongside the instruments-info check, monitor `last_kline_timestamp` per symbol. If the latest kline is older than `3 × interval` (e.g. for 1m candles, no fresh data in 3 minutes), flag the symbol as suspicious and downgrade in the universe.
3. **Auto-disable**: surface flagged symbols on a dashboard alert; on confirmed delisting, exclude from `signal-service` emission and from `trade-execution-service` intake.

The fix is sized as ~1 day. It hasn't been built because XMR was a single incident and the universe is small enough to monitor manually. As the universe grows (planned: 30+ pairs after the 2026 expansion), automated detection becomes essential.

### Listings — not yet capitalized

We do not currently trade fresh listings. Adding a symbol to our universe is a deliberate human decision based on signal-quality history, not an automated capture of listing-pump dynamics. This is a deliberate scope choice — listing-pump strategies require a fundamentally different execution path (event-driven, news-feed integration, very short holding windows) than our setup-detector + dimension-score pipeline.

## Sources

1. **Coinbase Asset Listing methodology.** https://www.coinbase.com/legal/listing-and-trading-rules — Coinbase's published rubric for new-asset listings. The basis for understanding what an "announcement" actually means at procedural level.
2. **Liu, Sheng, Wang (2023), "The 'Coinbase Effect' Revisited: Decay of a Classic Crypto Anomaly."** Working paper — available via SSRN. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4498611 — Empirical update showing the pump's diminishing scale post-2021.
3. **Howell, Niessner, Yermack (2020), "Initial Coin Offerings: Financing Growth with Cryptocurrency Token Sales."** *Review of Financial Studies* 33(9). https://academic.oup.com/rfs/article-abstract/33/9/3925/5868414 — ICO/listing-event return dynamics; baseline academic treatment.
4. **Binance announcement archive.** https://www.binance.com/en/support/announcement — Primary source for listing and delisting decisions. Searchable archive.
5. **Bybit announcement portal.** https://announcements.bybit.com/en/?category=delistings — Bybit's delisting notices; the relevant feed for our perpetual trading universe.
6. **CryptoCompare delisting tracker.** https://www.cryptocompare.com/ — Aggregates delisting events across exchanges; useful for universe maintenance even without a paid feed.
7. **Internal: `techdebt/2-2-silent-delisting-detection-gap.md`** — Our open techdebt note covering the XMR incident and the proposed detection fix.
