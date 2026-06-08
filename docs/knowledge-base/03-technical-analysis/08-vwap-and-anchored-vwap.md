# VWAP and Anchored VWAP

> Session VWAP is institutional execution's benchmark price — large funds measure their slippage against it. Anchored VWAP repurposes the same math as a dynamic structural support/resistance line, anchored to a meaningful event. Crypto's lack of a "session" makes anchored VWAP the more useful variant for signal-side work.

## Definition

**Session VWAP** (Volume-Weighted Average Price) is the cumulative volume-weighted mean price from the start of a trading session to the current bar:

```
VWAP[t] = sum(price[i] * volume[i], i=session_start..t) / sum(volume[i], i=session_start..t)
```

Where `price[i]` is typically the typical price `(high + low + close) / 3` of bar `i`.

The session reset is what defines it. On equities, "session" is unambiguous — open at 9:30 ET, close at 16:00 ET, reset overnight. On crypto, "session" is a convention: most platforms use UTC day boundaries (00:00 UTC reset) but it's arbitrary. The reset point is the indicator's fundamental weakness for 24/7 markets.

**Anchored VWAP (AVWAP)** is the same math but anchored to a user-specified starting bar instead of a session reset. Anchor to a significant swing low, a news event, a major candle close, an ETF approval — the AVWAP from that point forward represents the volume-weighted "fair price since the event."

Developed by Paul Levine in the mid-1990s as part of his MIDAS system and popularized for retail by Brian Shannon and others. ([Wikipedia — VWAP](https://en.wikipedia.org/wiki/Volume-weighted_average_price); [MQL5 — Institutional Anchored VWAP](https://www.mql5.com/en/code/71075))

## Why VWAP is the institutional execution benchmark

Per a Greenwich Associates survey, **over 40% of US institutional equity orders use VWAP as the primary execution benchmark**. ([Trading Revealed — VWAP institutional benchmarking](https://www.trading-revealed.com/education/vwap-in-modern-markets-strategic-calculation-institutional-benchmarking-and-algorithmic-implementation/))

The reason is mechanical. A pension fund needs to buy $200M of a stock today. Buying all of it at once moves the market against itself. The solution is to schedule the order across the trading day proportional to historical volume distribution — buy 1% in the first 5-minute window, 1.5% in the next, etc. The natural performance benchmark for this strategy is whether the average execution price beat the day's VWAP. If you "beat VWAP" by 5 bps, you executed well. If you missed VWAP by 10 bps, you slipped.

This is why VWAP appears on every institutional trading platform — not because traders read it as a signal, but because *every desk* is measuring its own execution against it.

## Why retail uses **Anchored** VWAP, not session VWAP

Session VWAP is meaningless for swing/position traders, because the daily reset throws away cumulative information. AVWAP from a significant low gives a *persistent* dynamic level — bouncing off the AVWAP confirms the move's strength; falling decisively below it indicates a regime change.

Common anchor choices in crypto retail:

- **Major swing low / high** — the AVWAP from a multi-week pivot acts as dynamic support/resistance.
- **ETF approval bar (Jan 10, 2024 for BTC; Jul 23, 2024 for ETH)** — separates pre-approval and post-approval price action.
- **Cycle low / cycle high** — useful as a long-horizon trend gauge.
- **Earnings-equivalent events** — for crypto, this could be a halving, a major protocol upgrade (ETH Merge, etc.), or a CPI/FOMC reaction bar.

When **multiple anchored VWAP lines from different events converge near the same price** alongside high-volume nodes from a volume profile, that price is a high-confidence structural level. This is the "AVWAP cluster" technique that Brian Shannon teaches in his materials.

## When VWAP works

- **Intraday institutional benchmarking.** On regulated equity-style sessions, session VWAP is the gold standard. For crypto where some institutions trade fixed-window blocks, the same applies on the UTC-day or NY-day window.
- **AVWAP as dynamic support in confirmed trends.** A strong uptrend with price respecting the AVWAP from the prior swing low is a high-confidence trend.
- **AVWAP confluence with horizontal S/R.** When AVWAP coincides with a swing-high resistance or a high-volume node, the level is sturdier than either signal alone.

## When VWAP fails

- **Session VWAP on 24/7 markets is mostly cosmetic.** The 00:00 UTC reset is arbitrary and doesn't correspond to any natural participant rotation.
- **AVWAP from poorly-chosen anchors is noise.** Anchoring to a random bar produces a random line. The anchor must correspond to a meaningful event that actually changed positioning.
- **In low-volume regimes.** VWAP weights by volume; in low-volume sessions, individual high-volume bars (often algos rebalancing) dominate the calculation disproportionately.
- **For backfilled data.** AVWAP is path-dependent — it needs every bar from anchor to present, with correct volume. Missing bars produce wrong VWAP values, sometimes by significant amounts.

## What we do today (in projectr-x)

**VWAP is not currently computed by `IndicatorCalculator`** and is not consumed by any detector. The volume signal we *do* use is the bar-relative volume ratio in `LiquiditySweepDetector.hasVolumeConfirmation` (1.3× the 3-bar trailing average). Discussed in `05-volume-analysis.md`.

Forward-looking opportunities:

1. **Session VWAP on UTC-day** as a `Technical` dimension input — distance-from-VWAP and slope-of-VWAP. Low-effort: implement in `IndicatorCalculator`, surface via `AnalyticsService`.
2. **Anchored VWAP from the most recent multi-day swing** as a structural support/resistance overlay — replacing or augmenting the pivot-based S/R in `calculateSupportResistance`. Medium effort: requires a swing-pivot detection upstream of the AVWAP computation.
3. **Multi-anchor AVWAP cluster detection** — finding price levels where multiple AVWAPs from independent events converge. High effort: but is the highest-quality target-placement enhancement realistic from TA-only inputs.

The decision to defer VWAP is a sequencing choice — the existing volume gate in LS handles the "is this a high-conviction bar" question at the per-bar level, and we want to validate the v4/v5 changes before adding new signal complexity.

## Sources

1. [Wikipedia — Volume-Weighted Average Price](https://en.wikipedia.org/wiki/Volume-weighted_average_price) — definition, history, the Levine anchored-VWAP origin.
2. [Trading Revealed — VWAP in Modern Markets](https://www.trading-revealed.com/education/vwap-in-modern-markets-strategic-calculation-institutional-benchmarking-and-algorithmic-implementation/) — institutional benchmarking, the Greenwich Associates 40% number.
3. [Trader Dale — VWAP & Volume Profile Combos](https://www.trader-dale.com/stop-guessing-start-winning-the-ultimate-guide-to-vwap-volume-profile-combos/) — practitioner-flavored treatment of AVWAP + volume profile confluence.
4. [Tradingshastra — VWAP Institutional Indicator Guide](https://tradingshastra.com/vwap-institutional-indicator/) — institutional-flow framing.
5. Shannon, *Maximum Trading Gains with Anchored VWAP* — Brian Shannon's book on the technique. Industry-standard reference for AVWAP-based discretionary trading.
6. [Britannica Money — Volume-Weighted Average Price](https://www.britannica.com/money/volume-weighted-average-price) — encyclopedia-style clean overview.
7. [eplanetbrokers — VWAP Complete Guide](https://eplanetbrokers.com/training/vwap) — supplementary practitioner explainer.
