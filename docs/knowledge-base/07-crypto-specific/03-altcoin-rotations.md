# Altcoin Rotations & BTC Dominance

> Capital rotates between BTC, ETH, and the long tail of altcoins on multi-week cycles that show up cleanly in three series: BTC dominance, ETH/BTC ratio, and the total altcoin market cap excluding BTC + ETH ("OTHERS"). Reading the rotation phase tells you whether to look for setups in majors or in tail.

## Definition

**BTC dominance** = `BTC_market_cap / total_crypto_market_cap`. Published live by TradingView (`CRYPTOCAP:BTC.D`) and CoinMarketCap. Historically oscillates between ~40% (peak alt seasons) and ~70% (bear-market BTC consolidation). Dominance is a position on a spectrum, not a fundamental quantity — it conflates supply growth in altcoins with their relative price action.

**ETH/BTC ratio** = `ETHUSDT / BTCUSDT`. The cleanest single read of "is risk-on within crypto?" When ETH outperforms BTC, the appetite for second-tier risk is growing. When ETH bleeds against BTC, capital is consolidating into the safer crypto asset. The ratio's 200-day SMA is a common regime divider.

**OTHERS market cap** = total crypto cap minus BTC minus ETH. TradingView ticker `CRYPTOCAP:OTHERS`. Rising OTHERS while BTC dominance falls is the textbook "alt season."

### The four-phase rotation pattern (folk wisdom; weakly supported)

A widely-cited "alt rotation map" claims capital flows:

1. BTC pumps first → BTC dominance rises.
2. BTC consolidates → ETH catches up → ETH/BTC pumps.
3. ETH consolidates → large-cap alts (BNB, SOL, ADA, etc.) pump.
4. Large caps consolidate → mid-cap and meme alts pump.
5. Cycle ends with parabolic small-caps + late-stage BTC blow-off → reversal.

This is **descriptive of past cycles, not predictive**. The 2017 → 2018 cycle showed it cleanly. The 2020 → 2022 cycle showed a messier version (DeFi summer overlay, ETH-merge anticipation). The 2023 → present cycle has been dominated by BTC + a handful of "narrative" alts (SOL, AI tokens, memes) rather than broad rotation. Treat it as a regime hypothesis, not a calendar.

## When it works

- **As a positioning lens.** Knowing "we're 6 weeks into ETH outperforming BTC by 15%" is useful context for sizing alt positions in our 13-pair universe.
- **For pair-trading.** Long underperformer / short outperformer when ETH/BTC reaches multi-year extremes has historically mean-reverted. The trade requires a 1–4 week horizon — not our short-term setup, but a feature for a future "rotation detector."
- **Risk-off filtering.** When dominance is rising sharply AND total cap is falling, the alt half of a portfolio is structurally bid-less. Detector strategies that fire BUYs on long-tail alts in this regime have low forward expectancy. We approximate this with `MarketRegimeService` on BTC, but a dominance-aware version would be sharper.
- **Sector rotation.** Inside the alt complex, capital rotates between L1 narratives, DeFi narratives, AI tokens, memes. Tracking sector indices (Glassnode publishes some; Messari maintains custom baskets) is the equity-style analog.

## When it fails

- **As a market-timing tool.** Dominance has no theoretical equilibrium — it's a relative measure with no anchor. Calls of "dominance has bottomed at 50%, alt season imminent" have been wrong as often as right.
- **Supply distortion.** Stablecoins make up ~5–10% of total crypto cap. Stablecoin issuance changes shift dominance numerically without any actual price action. Always check the underlying components.
- **Concentration in a few alts.** "Alt season" 2024-style was driven by SOL + a handful of memecoins. Calling it a broad rotation oversold what was actually narrow concentration. A 13-pair USDT-perp universe (our setup) is exposed to this concentration risk.
- **Macro overrides.** A Fed pivot or geopolitical shock collapses all of crypto in correlation. Dominance becomes meaningless when everything falls 30%.
- **Self-fulfilling narrative breaks.** When enough traders are positioned for "alts next," they front-run by buying alts → which pumps short-term → drains liquidity from BTC briefly → looks like a rotation → reverses violently when the late buyers exit. This kind of unstable equilibrium has been the rule, not exception, since 2021.

## What we do today (in projectr-x)

Nothing direct. Our regime detector reads BTC only:

- `MarketRegimeService` uses BTC 60d daily candles → classifies BULL/BEAR/CHOP/UNKNOWN.
- The 13-symbol universe (BTC, ETH, BNB, SOL, XRP, ADA, DOGE, AVAX, LINK, DOT, LTC, ATOM, NEAR — XMR delisted) is treated uniformly under that BTC-derived regime.

This is an acknowledged simplification. An ETH-leg signal during a BEAR-on-BTC regime could realistically be in an ETH-specific BULL phase, but our engine flags it under BTC's BEAR.

### Implementation sketch (not yet built)

The natural extension:

1. **`RotationService`** — periodic (15-min) job that computes:
   - BTC dominance from CoinMarketCap or CoinGecko API
   - ETH/BTC ratio from `candles` table
   - Each symbol's 7-day return vs BTC's 7-day return → `relativeStrength` score

2. **Per-symbol regime modifier.** SignalEngine's `determineSignalLabel` currently uses `marketRegime` (BTC). Augment it with `relativeStrength`: an alt in BTC-BEAR but with positive 7d relative-strength gets a less-punishing SELL threshold; an alt in BTC-BULL but with negative relative-strength gets a tighter BUY threshold.

3. **New dimension or signal feature.** A 7th dimension, `Rotation`, or a feature on the existing `Macro` dimension. Avoid adding noise — only ship after backtesting against current outcome data.

The reason this isn't built yet: with only 13 symbols and median hold time under 4h, per-symbol relative strength changes too slowly to materially improve short-horizon signals. The feature pays off more for medium-horizon (1–7d) swing setups, which we don't currently emit.

## Sources

1. **Messari "State of Crypto" reports.** https://messari.io/research — Quarterly reports tracking dominance, sector flows, and alt-rotation patterns with primary data.
2. **Glassnode Insights — "Bitcoin Dominance and Altcoin Cycles."** https://insights.glassnode.com/ — Series on dominance shifts as on-chain regime indicators.
3. **CoinMetrics State of the Network.** https://coinmetrics.io/insights/state-of-the-network/ — Free weekly data-rigorous treatment. Search-friendly archive on rotation indicators.
4. **Wheatley et al. (2018), "Are Bitcoin Bubbles Predictable?"** https://arxiv.org/abs/1803.05663 — Statistical evidence that BTC's bubble cycles drag alt cycles by 2–8 weeks.
5. **Liu, Tsyvinski (2021), "Risks and Returns of Cryptocurrency."** *Review of Financial Studies* 34(6). https://academic.oup.com/rfs/article-abstract/34/6/2689/5868423 — Cross-sectional analysis showing crypto returns are dominated by an aggregate factor (BTC) — implies most "rotation" is a residual on top of a common factor.
6. **TradingView CRYPTOCAP indices documentation.** https://www.tradingview.com/symbols/CRYPTOCAP-BTC.D/ — Source for the BTC.D, ETHBTC, OTHERS series used by retail.
