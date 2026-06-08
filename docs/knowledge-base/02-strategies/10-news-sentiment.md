# News Sentiment

> Trade the direction of news flow before it fully diffuses into price. Theoretical edge is real at very short horizons; our implementation currently captures none of it, and the document below explains why.

## Definition

News-sentiment trading exploits the lag between when textual information becomes publicly available (a headline prints, a regulatory filing appears, a CEO tweets) and when its price impact fully manifests. The mechanism is well-documented:

1. **Information arrives** in unstructured form (article body, headline, social post).
2. **Sentiment is extracted** — typically a real-valued score on `[-1, +1]` per piece, aggregated to a per-asset score over a rolling window.
3. **Trading signal generated** from the cross-section of asset-level scores (long the most positive, short the most negative) or from time-series of a single asset's score crossing a threshold.
4. **Execution** at the prevailing market price; the bet is that the sentiment signal contains information not yet reflected in the price.

The academic literature is large and consistently finds *some* edge:

- **Tetlock (2007)** "Giving Content to Investor Sentiment" — Wall Street Journal's "Abreast of the Market" column has a measurable next-day market impact. Negative sentiment predicts modestly negative next-day returns.
- **Garcia (2013)** "Sentiment During Recessions" — Sentiment effect is much stronger during recessions than expansions; aggregate market mood matters when liquidity is constrained.
- **Heston & Sinha (2017)** "News vs. Sentiment" — Distinguishes news *content* from news *sentiment*; finds that 1-2 day predictive power persists in equity returns from news sentiment classifiers.

In crypto specifically the literature is younger but consistent:

- **Kraaijeveld & De Smedt (2020)** finds Twitter sentiment Granger-causes short-horizon returns for major coins.
- **Smales (2022)** finds Reddit sentiment has the strongest effect on small-cap altcoins where retail dominates flow.

The headline rule: **sentiment edge in crypto is real, but it lives at the seconds-to-minutes horizon, decays inside the hour, and is fully arbitraged at the daily level.** Beating it requires fast NLP, fast execution, and source-curation discipline.

## When it works

- **Sub-minute reaction to breaking news.** The SEC approval of the spot BTC ETF (Jan 10 2024) moved BTC by 6% in the 90 minutes following the announcement. A bot reading the SEC's filing-system feed and crossing the spread within 5 seconds had a defensible edge. The same news 4 hours later was fully priced in.
- **Regulatory and exchange-listing news.** Coinbase listing announcements for mid-cap altcoins reliably produce 15-40% spikes in the first 30 minutes. Binance delisting announcements produce the mirror image. Both are textual events with clear extractable signal.
- **Coordinated sentiment shocks.** A genuine "fear" cascade (Mt. Gox payouts, Silk Road wallet movements, Celsius bankruptcy) produces multi-day persistence in negative sentiment that maps to genuine selling pressure. Slower-than-headline-but-not-slow-enough horizon — useful for half-day positioning.
- **Cross-section of social media activity.** A coin with sudden 5×+ baseline Twitter mention volume has, on average, larger next-week return dispersion than a quiet coin. Volume of attention, not direction of attention, is the more robust signal — and it composes with the directional signal when present.
- **Earnings-equivalent events.** Major protocol launches (ETH Merge, Solana Firedancer, etc.) where the textual content is dense with technical detail and the price impact is conditional on interpretation — slow human-readable NLP can outperform fast keyword-counters here.

## When it fails

- **Latency.** Most sentiment APIs (CoinDesk, RSS aggregators) have 5-15 minute latency between news publication and feed availability. By the time the article is parseable, the price has already moved. Our pipeline is in this slow-feed category — see the "What we do today" section.
- **Sentiment classifier quality.** Bag-of-words lexicons (positive/negative dictionaries) get crypto vocabulary wrong constantly. "Crashed" is negative in a finance lexicon; in crypto slang it's neutral-to-positive ("the price crashed support and broke higher"). Modern transformer-based classifiers do better but require GPU-level inference for real-time use.
- **Sentiment vs price decoupling.** During the Nov 2022 FTX collapse, mainstream news sentiment turned violently negative *after* most of the price damage was done. The headline came hours-to-days behind the on-chain wallet activity that drove the actual selling. A sentiment trader following news would have shorted at the bottom.
- **Repeat headlines and duplicate content.** RSS aggregators duplicate articles across feeds. A single Reuters story syndicated to 30 sites looks like "30 negative articles" to a naive count-based scorer. Properly deduping at the URL/title level is mandatory and often skipped.
- **Sentiment regimes are unstable.** A keyword that predicted positive returns in 2021 (e.g., "institutional adoption") was reversed by 2023 (institutional adoption was now correlated with selling pressure as funds liquidated). The classifier's labels need continuous re-fitting; static lexicons decay.
- **Survivorship + selection bias.** The published academic results select on classifiers that found signal. The unpublished ones don't. Even credible papers often optimise lookback windows and threshold choices on the same data they report — a known statistical hazard in this literature.
- **Tier-1 institutional NLP is uncompetitive.** Bloomberg, Two Sigma, Citadel run news-sentiment pipelines with <1s latency and proprietary classifiers. Any edge accessible to a retail-tier feed has already been arbitraged. We're competing with very-deep pockets in the same trade.

## What we do today (in projectr-x)

**Empirically, our news-sentiment dimension has been noise.** This is documented honestly in the code and in CLAUDE.md.

The infrastructure:

- **`news-service`** collects articles from RSS feeds (CoinDesk, CoinTelegraph, Decrypt) and from CoinDesk's API via `CoinDeskApiProvider` (`services/news-service/src/main/java/com/cryptoradar/news/provider/CoinDeskApiProvider.java`).
- **`SymbolExtractor`** scans article titles + bodies for ticker mentions across our 13-symbol universe.
- **`SentimentAnalyzer`** (`services/news-service/src/main/java/com/cryptoradar/news/service/SentimentAnalyzer.java`) computes a per-article sentiment score using a **lexicon-based positive/negative word count**. The lexicon supports both exact word matching and prefix matching for stems of length ≥5 (so "drops", "surged", "rising" count without manually enumerating every inflection).
- **`DailySentiment`** aggregates per-symbol per-day average sentiment, persisted to PostgreSQL.
- **`signal-service`** consumes the per-symbol sentiment score as the **Sentiment dimension** in `MarketContext`. The dimension contributes to overall signal score with a small weight relative to Technical / Whale / Derivatives.

Why it's empirically noise:

1. **Lexicon limitations.** The pre-v4 lexicon was 17 positive + 17 negative root words with no inflection handling. Headlines like "Bitcoin drops on Fed concerns" scored 0 because only "drop" was in the set, not "drops". The v4 fix (`G.2: unblock sentiment feed for trading pairs`, commit `8963340`) added inflection variants and prefix matching, but the underlying problem remains — lexicon classifiers are blunt instruments.
2. **CoinDesk empty bodies.** Many CoinDesk API articles return with empty body text — only the title is scoreable. A 50-word title is too little signal for sentiment aggregation.
3. **Latency.** RSS feeds update on 5-15min polls; sentiment-aggregation runs on daily windows. By the time today's sentiment is computed, the price impact of any news in it has decayed.
4. **Source mix concentration.** CoinDesk + CoinTelegraph + Decrypt is three sources that often re-syndicate each other. Diversification across very-different source types (Reddit, Discord, Twitter, Telegram, regulator filings) would matter more than incremental NLP polish.

The pre-v4 inverted-derivatives bug aside, the Sentiment dimension's correlation with realised R has been low across the entire post-v4 measured window.

## Implementation sketch (to make it actually useful)

The order of operations matters — investing in NLP before fixing latency and source mix is wrong:

1. **Latency first.** Replace RSS polling with WebSocket / push-based feeds where available. CoinDesk has a websocket; Twitter (via paid API tier) does too. Target <60s from publication to scored signal. Without this, no NLP improvement will matter.
2. **Source diversification.** Add structured-feed sources: SEC EDGAR for filings, exchange API endpoints for listing/delisting announcements, GitHub commit/release events for protocol-side news. These are higher-signal-per-event than mainstream crypto press.
3. **Decay model.** Stop using flat daily aggregation. Use an exponentially-weighted score with a half-life of 60-120 minutes — most news edge decays inside 2 hours.
4. **Transformer classifier.** Replace the lexicon with a fine-tuned distilled BERT or similar on crypto-headline-labelled data. CryptoBERT and FinBERT are open-source starting points. Run inference on CPU for latency.
5. **Volume signal alongside direction.** Track mention-volume Z-scores per symbol independently of the sentiment-direction score. "ATTN surge + neutral sentiment" is a real signal class.
6. **Empirical validation.** Before consuming any improved dimension in the signal score, backtest it as a standalone predictor across our outcomes ledger. If the IC (information coefficient) doesn't clear 0.05 over a 30-day window, the dimension stays cosmetic, not weighted.

Each numbered step is roughly 1 week. Doing 1-3 (latency + source mix + decay) without 4-6 would still produce a meaningful upgrade — the current bottleneck is operational, not algorithmic.

## Sources

1. **Tetlock, P. C. (2007). "Giving Content to Investor Sentiment: The Role of Media in the Stock Market." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1540-6261.2007.01232.x — Seminal paper establishing that media sentiment predicts short-horizon equity returns. The methodology is directly portable to crypto.
2. **Loughran, T., & McDonald, B. (2011). "When Is a Liability Not a Liability? Textual Analysis, Dictionaries, and 10-Ks." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1540-6261.2010.01625.x — Created the standard financial sentiment lexicon; documents how general-purpose lexicons (LIWC, Harvard IV) misclassify financial text. Highly relevant to our lexicon design.
3. **Kraaijeveld, O., & De Smedt, J. (2020). "The predictive power of public Twitter sentiment for forecasting cryptocurrency prices." *Journal of International Financial Markets, Institutions and Money*.** https://www.sciencedirect.com/science/article/abs/pii/S1042443120300937 — Documents Granger-causal short-horizon predictive power of Twitter sentiment for crypto major returns.
4. **Smales, L. A. (2022). "Investor attention and the response of US stock market sectors to the COVID-19 crisis." *Review of Behavioral Finance*.** https://www.emerald.com/insight/content/doi/10.1108/RBF-09-2020-0223 — Methodology for distinguishing attention from sentiment in social-media-driven asset moves.
5. **Araci, D. (2019). "FinBERT: Financial Sentiment Analysis with Pre-trained Language Models." arXiv:1908.10063.** https://arxiv.org/abs/1908.10063 — Open-source BERT model fine-tuned on financial text; obvious replacement for our lexicon classifier.
6. **Cohen, L., Malloy, C., & Nguyen, Q. (2020). "Lazy Prices." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/jofi.12885 — Documents that *changes in* corporate filing text (not the text itself) predict future returns. Methodologically relevant for tracking text-delta signals rather than absolute sentiment.
