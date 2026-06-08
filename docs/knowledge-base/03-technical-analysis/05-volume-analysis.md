# Volume Analysis

> Volume is the ground truth that confirms or refutes a price move. A breakout on light volume is a failed test; the same breakout on 1.3-1.5× normal volume is a structural break worth trading.

## Definition

**Volume** is the count of base-asset units traded in a bar. On a perp contract it's the count of contracts (or notional in USDT-margined perps). It is a non-price-derivative observable — every other technical indicator is a function of OHLC, but volume is the only one that measures *how many participants* generated the price move.

The intuition: a 2% rally on 100 trades is a different beast from a 2% rally on 10,000 trades. The first might be one whale's market order through a thin book. The second represents broad participation. Same price chart, opposite signal quality.

## Key volume concepts

- **Volume confirmation.** A directional move accompanied by above-average volume is statistically more reliable than the same move on light volume. The classic Dow Theory tenet: trends persist when volume agrees with the direction.
- **Volume divergence.** Price makes a new high but volume doesn't — momentum is thinning. Mirror image for new lows. This is the volume analogue of MACD divergence.
- **Volume profile (Volume by Price).** A horizontal histogram showing total volume traded at each price level, regardless of time. **High-volume nodes (HVN)** are prices where the market spent transactional energy — these become natural support/resistance. **Low-volume nodes (LVN)** are gaps where the market traded through quickly; these tend to break easily on re-tests.
- **VWAP (Volume-Weighted Average Price).** Mean price weighted by per-bar volume over a session or anchored period. Treated in `08-vwap-and-anchored-vwap.md`.

## Why volume matters on liquidity sweeps specifically

A liquidity sweep is mechanically a stop-run: market orders cascade through a swing low, triggering retail stops and forced liquidations. The cascade *must* show in volume — by definition stops fire market orders, which print volume.

The opposite case — a wick that pierces a swing low on **normal** volume — is much more likely to be a single whale's iceberg refresh or a thin-book artifact. It doesn't represent broad capitulation, so the reversal thesis is weaker.

This is the rationale for [`LiquiditySweepDetector.MIN_VOLUME_RATIO = 1.3`](../../services/signal-service/src/main/java/com/cryptoradar/signal/detector/LiquiditySweepDetector.java) checked against the average of the prior 3 bars: the trigger bar's volume must be at least 1.3× the baseline. We chose 1.3 (not 2.0 or 1.5) because:

- 1.5+ is too restrictive on lower-volume altcoins where the baseline itself is noisy.
- 1.0 or 1.1 doesn't filter much — almost half of bars are above their 3-bar trailing average.
- 1.3 catches "obviously elevated" without rejecting genuine sweeps that happen to be on moderate volume.

The detector also **degrades gracefully** when volume data is missing: `hasVolumeConfirmation` returns `true` (skip the filter) rather than rejecting the signal. Legacy `CandleBar` records (5-arg constructor) default to `volume == 0`, and we'd rather miss the volume gate than silently drop every signal on backfilled data.

## When volume confirmation works

- **Stop-run reversals.** As above — sweeps without volume are weaker. The LS detector's filter directly encodes this.
- **Trend breakouts.** Breaking a long-tested resistance on heavy volume is a regime change. Breaking it on light volume is usually a fakeout.
- **Climax exhaustion.** A multi-standard-deviation volume spike at the end of a long move often marks a top or bottom (selling/buying climax). Not currently encoded in our detectors.
- **Confirming HVNs/LVNs as targets.** Targets sitting at high-volume nodes are more reliable take-profit levels than mid-air levels.

## When volume confirmation fails

- **Cross-venue distortion.** Volume on Binance is not directly comparable to volume on a small DEX. Reading a "global volume aggregator" without normalization can produce false signals.
- **Spoofing.** Large orders posted then cancelled don't show in trade volume but do appear in book depth. A symbol with persistent spoofing has a real "trader interest" picture different from its tape.
- **Wash trading.** Smaller exchanges historically inflated reported volume via internal washes. Less of an issue on Tier-1 CEXes (Binance, Bybit, Coinbase, OKX) but a constant concern on Tier-2/3 venues. Use Coinmetrics or Kaiko data when wash-trading hygiene matters.
- **Around exchange-side glitches.** Bybit and Binance both have produced "volume spikes" that were really gateway retransmissions or stuck-channel re-replays. Volume can be artifactual.
- **In bot-driven markets.** Some altcoins are 80%+ HFT bots cycling inventory; volume there doesn't reflect directional interest from any meaningful population of traders.

## What we do today (in projectr-x)

- **Volume is captured per-bar** in `CandleBar.volume()` via the market-data-service Binance kline pipeline. New 6-argument constructor includes volume; legacy 5-arg paths set it to 0.
- **`LiquiditySweepDetector.hasVolumeConfirmation`** is the only volume gate in the signal stack:
  - `MIN_VOLUME_RATIO = 1.3` against the average of the prior 3 closed bars (`VOLUME_BASELINE_BARS = 3`).
  - Degrades to true when trigger volume = 0 or no valid baseline exists.
- **`TrendContinuationDetector` does not use volume** as a gate. This is a known opportunity — trend-continuation entries on rising volume have empirically outperformed entries on flat volume in equity literature; we have not measured this on our crypto data yet.
- **Volume profile / VbP is not computed.** Our S/R uses swing pivots, not volume nodes. This is the largest unexplored TA enhancement for target placement quality.

## Sources

1. Pring, *Technical Analysis Explained* — Volume chapter is the standard reference for confirmation/divergence rules.
2. [StockCharts ChartSchool — Volume](https://chartschool.stockcharts.com/table-of-contents/chart-analysis/volume) — clean overview of volume confirmation rules.
3. [TradingView — Volume Profile documentation](https://www.tradingview.com/support/solutions/43000502040-volume-profile/) — standard reference for VbP terminology (POC, value area, HVN/LVN).
4. [BabyPips — Volume Spread Analysis](https://www.babypips.com/learn/forex/volume-and-the-markets) — accessible primer.
5. [Trader Dale — The Ultimate Guide to VWAP & Volume Profile Combos](https://www.trader-dale.com/stop-guessing-start-winning-the-ultimate-guide-to-vwap-volume-profile-combos/) — practitioner-flavored synthesis of volume profile and VWAP combined.
6. Kaiko / Coin Metrics — research on wash-trading detection and exchange-volume quality. See `09-sources/04-practitioner-blogs.md`.
