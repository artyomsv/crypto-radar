# Liquidity Sweep and Reversal

> A wick pierces an obvious swing high or low — triggering stops and liquidations — then closes back inside the range. The trade fades the wick, betting the move was manipulation, not a real breakout.

## Definition

The "liquidity sweep" (also: "stop hunt", "liquidity grab", "swing failure pattern", "SFP") is a price-action pattern where the market briefly trades beyond an obvious prior swing level, runs the stop-loss and liquidation orders clustered just beyond that level, then reverses back through the level on the same or next bar.

Mechanically, the pattern has a logical basis that doesn't depend on any one trading philosophy:

- Retail traders place stops just beyond the most recent swing high/low — this is a directly observable behaviour and Bybit's liquidation map shows exactly where the leveraged liquidation prices cluster.
- A market maker, arbitrageur, or large directional participant can profitably push price into those resting orders to fill their own size at better prices, then unwind the temporary push.
- The visible artefact is a candle with a long wick beyond the level, a small body, and a close back inside the range.

The pattern is most associated with two practitioner schools: **ICT** (Inner Circle Trader / Michael Huddleston) and **Smart Money Concepts** (SMC) on the FX/crypto retail side. Both pile on substantial folklore (order blocks, fair value gaps, kill zones, breakers) where the empirical basis weakens, but the **core "wick beyond level + reclaim" geometry** is well-documented in academic microstructure literature too — Bouchaud & Bonart (2018) cover stop-hunting under their treatment of order-book pressure and reflexive liquidations.

This document borrows the geometry while keeping rigorous statistical filtering. We do not borrow the broader cosmology (no kill zones, no PD arrays, no Wyckoff schematics overlaid).

## When it works

- **Liquidity-heavy levels.** Round numbers (BTC at $100k, ETH at $4k), all-time highs/lows, prior-day H/L, and visible swing pivots all attract stops. The denser the resting-order pool, the bigger the reversion when it gets eaten.
- **High-leverage altcoins.** OI/Float ratio on perp markets predicts the magnitude of the reversion — a sweep on a coin where perp OI > 30% of spot market cap means a violent unwind once the stops finish firing. Bybit's liquidation feed (`services/derivatives-service/src/main/java/com/cryptoradar/derivatives/provider/BybitLiquidationProvider.java`) is the live evidence.
- **Counter-positioning.** A sweep is profitable to fade when the market is one-sided into the level. Funding rate persistently positive + long-short ratio skewed long + price grinding into a swing high = the stops are below and the breakout is structurally crowded. Funding flat or neutral = the "manipulation" thesis is weaker.
- **Higher-timeframe context.** A 4h sweep that reverses into a daily uptrend has institutional dip-buying as a tailwind. A 4h sweep against the daily trend is just an oversold bounce — different trade.
- **Sufficient ATR.** Low-ATR regimes produce wicks that match the pattern geometrically but carry no directional information — they're indistinguishable from noise.

## When it fails

- **Real breakouts dressed as sweeps.** The hardest failure mode: a candle wicks past the level, looks like a rejection, then the *next* bar continues through. The first bar was the early shoot, not a sweep. There is no purely-price-action way to disambiguate ex-ante; the only defence is a tight stop and confluence with non-price-action features (funding flip, OI drop, liquidation print).
- **Range collapses.** A swing low that gets swept multiple times eventually fails for real — each successive sweep weakens the level (sellers above it get more confident, buyers below it run out of dip-buying capacity). The 4th sweep of an obvious low is often the real breakdown.
- **Low ATR / range-bound regime.** In sideways markets, every move is a "sweep" of one side or the other. The signal has no information content because the alternative outcome rate is too high.
- **ICT/SMC overfit.** A community of traders watching the same patterns means a self-fulfilling component (front-running the bounce) and an inevitable arbitrage component (well-capitalised traders force the move past the obvious reversal point to liquidate the SFP traders). Our `MIN_PIERCE_ATR_FRACTION = 0.3` filter exists specifically to ignore the shallow, easily-front-run sweeps.
- **Fee drag on small-stop versions.** A sweep with a 0.3% stop and a 0.6% target nets to ~0.1% after Bybit's 0.11% round-trip taker fees. This is the failure mode that produced 46 of 54 LS signals on LTC pre-fix — phantom trades that lost the spread.

## What we do today (in projectr-x)

`LiquiditySweepDetector` (`services/signal-service/src/main/java/com/cryptoradar/signal/detector/LiquiditySweepDetector.java`) is the concrete implementation. The detector fires on **4h closed bars** and requires a long list of statistical filters before signalling. From the source:

- **Minimum history**: 8 bars (6 swing bars + 1 trigger + buffer).
- **Sweep depth**: trigger bar must pierce the prior swing high/low by `≥ 0.3 × ATR14` (`MIN_PIERCE_ATR_FRACTION`). Tightened from 0.1 to 0.3 after outcome analysis — shallow pokes are indistinguishable from intrabar jitter.
- **Rejection wick**: the wick on the swept side must be `≥ 0.5 × body` (`MIN_WICK_BODY_RATIO`).
- **Reclaim depth**: the close must reclaim the level by `≥ 0.3 × body` (`MIN_RECLAIM_BODY_RATIO`). A one-tick reclaim is a failed retest, not a rejection.
- **ATR-pct floor**: skip when `ATR14 / price < 0.003` (`MIN_ATR_PCT`). Low-ATR regimes generate the pattern with no signal.
- **Drift cap**: refuse the entry when current price has drifted `> 0.5%` from the trigger close (`MAX_DRIFT_PCT`). Past 0.5% of drift, the reversal R:R degrades.
- **Volume confirmation**: trigger bar must show `≥ 1.3×` the average volume of the prior 3 bars (`MIN_VOLUME_RATIO`). Degrades gracefully if volume data is missing.
- **Derivatives confluence**: derivatives dimension cannot oppose the reversal direction by more than 5 points (`DIM_DERIVATIVES_TOLERANCE`) — tightened from 15 to 5 because the whole thesis depends on crowded positioning being wrong.
- **Stop**: `wick − (0.5 × ATR14)` for longs, mirrored for shorts. Widened from 0.2 to 0.5 ATR after MAE analysis showed 23 of 53 stops had MAE ≥ 1.5R — price was wicking past the stop and then reversing.
- **Min risk**: `LS_MIN_RISK_PCT = 0.015` (1.5% of entry). Cuts fee drag from 22%/R at 0.5% stops to ~7%/R at 1.5% stops.
- **Target**: `max(entry + 5R, structural swing on the opposite side)`.
- **Trail**: default `TrailConfig` (0.5R offset) — tighter than trend-continuation because the reversal payoff is typically faster and less right-tail-skewed than a continuation move.

Outcome tracking writes a row to `signal_outcomes` per fire with `strategy='liquidity-sweep'` so the win-rate, MFE/MAE, and R-distribution can be sliced separately from dimension-scoring and trend-continuation in `SignalResource.getMetrics`. The dedup key `(symbol, direction, strategy)` keeps repeated sweep signals from polluting the ledger while a prior LS outcome is still open.

## Implementation sketch

Already implemented — see the file linked above. Open improvements worth considering:

- **Liquidation-cluster overlay**: tighten the filter when the swept level coincides with a high-density liquidation cluster from `services/derivatives-service/src/main/java/com/cryptoradar/derivatives/service/LiquidationMapService.java`. This is the highest-conviction subset of the pattern and currently not measured separately.
- **Sweep-of-sweep**: detect when the swing being swept is itself a recent sweep wick (compound failure pattern). Currently the swing computation just takes the prior 6-bar extreme regardless of how the extreme formed.
- **Multi-timeframe alignment**: require the daily trend to agree with the reversal direction. We don't enforce this today; might add edge at the cost of fewer signals.

## Sources

1. **Bouchaud, J.-P., Bonart, J., Donier, J., & Gould, M. (2018). *Trades, Quotes and Prices: Financial Markets Under the Microscope*. Cambridge University Press.** — Academic treatment of order-book dynamics, hidden liquidity, and the empirical evidence for "predatory" trading around resting orders. Sections 7-9 cover the microstructural basis for stop-hunting.
2. **Harris, L. (2003). *Trading and Exchanges: Market Microstructure for Practitioners*. Oxford University Press.** — Classic reference for how stops cluster, how market makers price knowing this, and why predatory trading is profitable for those who can see resting-order locations.
3. **Cohen, K. (2020-2024). *ICT Mentorship — Public YouTube archive*.** https://www.youtube.com/@InnerCircleTrader — Note: ICT material is folklore-heavy and unevidenced in places. We cite it because the *vocabulary* (liquidity grab, swing failure pattern, equal highs/lows) maps to observable patterns, while explicitly ignoring the unfalsifiable layers (kill zones, MMXM cycles).
4. **Brunnermeier, M. K., & Pedersen, L. H. (2005). "Predatory Trading." *Journal of Finance*.** https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1540-6261.2005.00781.x — Peer-reviewed treatment of how informed traders profit by pushing prices through other participants' stop-loss levels.
5. **Easley, D., López de Prado, M., & O'Hara, M. (2012). "Flow Toxicity and Liquidity in a High-Frequency World." *Review of Financial Studies*.** https://academic.oup.com/rfs/article/25/5/1457/1568510 — VPIN-style measures of order-flow toxicity; relevant for distinguishing a real informed sweep from random wick noise.
6. **projectr-x empirical findings — `10-projectr-x-mapping/04-empirical-findings.md`.** Internal observations: the v3-era 23/53 stop-MAE-≥1.5R distribution that motivated the buffer widening; the 46/54 LTC contamination that motivated the ATR-pct and risk-pct floors.
