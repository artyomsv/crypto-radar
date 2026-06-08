# Funding Rate Arbitrage

> When perpetual futures funding is persistently positive, longs pay shorts. Be short the perp, long the spot of the same amount, collect funding minus borrow/financing costs. The textbook crypto cash-and-carry.

## Definition

Perpetual futures contracts have no expiration. To prevent the perp price from drifting away from spot, exchanges implement a periodic "funding payment" that exchanges money between longs and shorts based on the perp-vs-spot price difference. Bybit and Binance settle every 8 hours (00:00, 08:00, 16:00 UTC); some venues use 1h or 4h cycles.

The funding rate at each settlement is roughly:

```
funding_rate ≈ premium_index_TWAP + clamp(interest_rate - premium_index_TWAP, ±0.05%)
```

When perp trades above spot (positive premium), funding is positive and longs pay shorts. The arbitrage is straightforward:

- **Short the perp** for size S.
- **Buy the spot** (or hold equivalent inventory) for the same size S.
- **Net delta exposure**: zero. The two legs hedge each other 1:1.
- **Income stream**: funding payments earned on the short perp leg, paid at each funding interval.
- **Costs**: spot purchase fees, perp open/close fees, any spot custody cost or staking-opportunity cost foregone, plus the borrow cost if the spot leg is financed.

This is the **basis trade** or **cash-and-carry** in classical futures terminology, adapted for the perp's continuous funding stream instead of a discrete expiration premium. It is one of the very few crypto strategies with a genuinely defensible no-directional-bet structure.

## When it works

- **Sustained positive funding regimes.** During the 2020-21 bull, BTC perp funding averaged 0.04-0.08%/8h on Bybit/Binance — annualised that's 40-100%. With careful execution, real funds harvested 20-40% net annualised returns from cash-and-carry on majors.
- **Specific funding spike events.** A meme-driven rally can push altcoin funding to >0.5%/8h for hours-to-days; if you can size into the short perp + spot leg quickly, single-event annualised returns are absurd (1000%+ on a 48h window).
- **Stable-coin-denominated.** USDT-quoted perps + USDT-quoted spot (or USDC variant) allow the entire position to remain dollar-denominated, removing the multi-leg currency complexity that plagues equity-FX carry trades.
- **Cross-venue arbitrage.** Short the perp on the venue with the highest funding, long the spot wherever it's cheapest. Capital-intensive but the dispersion across Binance/Bybit/OKX funding is real and exploitable.
- **As a yield overlay on existing spot holdings.** If you already hold BTC/ETH spot for fundamental reasons, layering on a short-perp hedge during high-funding regimes converts spot-storage cost into a positive yield stream without changing your directional exposure.

## When it fails

- **Funding flips negative.** This is the dominant failure mode. After a major spot drop, perp typically trades below spot for days-to-weeks and funding flips sharply negative (shorts pay longs). A short-perp position now bleeds funding instead of collecting it. The Mar 2020, May 2021, and Nov 2022 events all produced multi-day negative funding regimes that wiped weeks of accumulated carry.
- **Spot-perp basis blows out adversely.** If you entered when perp was 0.5% above spot, you marked-to-market favourably immediately. If perp then crashes to 2% *below* spot before you close, you eat the entry-vs-exit basis loss — which can exceed the cumulative funding collected.
- **Liquidation on the perp leg.** A short perp with leverage 2-3× can liquidate during a rapid spot rally even though the cash-and-carry is "delta neutral" — because the leverage on the perp leg is greater than the spot leg's offsetting move. The May 2021 BTC rally from $35k to $58k liquidated many carry traders who hadn't sized conservatively. Correct sizing requires the perp leg to use leverage near 1× of equity, which capital-inefficient.
- **Execution slippage on the two legs.** Opening the spot leg at $60,500 and the short perp leg at $60,400 (because of microsecond timing) leaves a 0.17% directional residue. Repeating this on entry and exit eats the funding edge.
- **Funding fees + trading fees + spreads stack up.** A naive backtest assuming "0.05%/8h funding → 55% annualised" ignores: 0.055% taker fee × 4 legs (open spot, open perp, close perp, close spot) = 0.22% per round-trip; spot maker rebate possibly negative; perp maker rebate possibly positive. The realistic net edge is materially smaller than the gross funding.
- **Stablecoin de-peg risk.** USDT is not USD; in a USDT de-peg event (and these have happened — Oct 2018, May 2022) both legs of the trade get repriced unpredictably. The "delta-neutral" property assumes the quote currency is stable, which is empirically only ~99% true.
- **Exchange counterparty risk.** Capital sits on the exchange as collateral on both legs. FTX showed that "delta neutral on the exchange" still means "you go to zero if the exchange dies." Splitting legs across exchanges removes this but introduces transfer-latency risk during stress events when withdrawals are paused.
- **Capacity decay.** When too many funds run cash-and-carry, the funding rate compresses toward equilibrium (0.01-0.03%/8h). Returns fall and the strategy approaches the cost of running it. 2022-2024 has seen this compression on BTC; less so on altcoins.

## What we do today (in projectr-x)

**We do not run cash-and-carry.** Our execution path is single-leg (perp only) and our infrastructure (`services/trade-execution-service/`) was deliberately scoped to directional signal execution.

The funding rate **data** is collected via `services/derivatives-service/src/main/java/com/cryptoradar/derivatives/service/DerivativesService.java::refreshFundingRates` and feeds the Derivatives dimension scorer in `signal-service`. Persistent positive funding skews the Derivatives dimension *bearish* (consistent with crowded-long positioning being a contrarian signal) and contributes to SELL opportunity identification. This is using funding as a *sentiment* feature, not as an arbitrage opportunity.

The `FundingRate` model (`services/derivatives-service/src/main/java/com/cryptoradar/derivatives/model/FundingRate.java`) stores symbol, rate, next-funding-time, mark-price. Historical snapshots go to the `funding_rates` hypertable for time-series queries.

What the existing data infrastructure could enable, with execution-side additions:

- **Identify high-funding windows.** Query the funding_rates hypertable for symbols where the trailing 8h-funding cumulated above some threshold (e.g., >0.15%).
- **Signal-overlay.** Tag a Bybit-perp short signal with a "carry tailwind" flag when funding is favourable; surface this in the UI alongside dimension scores.

## Implementation sketch (if we ship cash-and-carry)

Minimum viable basis trade automation:

- **New module**: `BasisTradeService` inside `trade-execution-service` (or a new `arbitrage-service`).
- **Trigger**: persistent 8h funding > 0.10% over the last 24h on a symbol where Bybit lists both perp and spot. Sustained-positive-funding requirement filters out single-spike noise.
- **Sizing**: capital-efficient ratio — leverage 1.5-2× on the perp leg (allowing for adverse basis moves), 1× on the spot leg. Total exposure capped at a configurable fraction of equity.
- **Execution**: simultaneous market orders on both legs via a new dual-leg `OrderPlacer`. Accept the slippage; the alternative (limit orders on both) introduces leg-failure risk that's worse than market slippage.
- **Monitoring**: per-cycle funding accumulation logged to a new `basis_trade_events` table. Daily ROIC calculation. Auto-close trigger when (a) funding flips negative for ≥3 consecutive intervals, (b) realised annualised return drops below configurable threshold, or (c) PnL on the combined two-leg position drops below the cumulative funding earned (basis-blowout protection).
- **Effort**: ≥3 weeks. Two-leg execution and reconciliation alone is ~1 week; the monitoring/auto-close logic is the bulk of the remaining work.

The bar to clear before building: **measure historical basis-trade PnL** on our existing funding_rates time-series, net of realistic fees and slippage. If the implied annualised return over the last 90 days is <8% net, it doesn't clear the operational complexity hurdle for our prototype stage.

## Sources

1. **Hull, J. C. (2017). *Options, Futures, and Other Derivatives* (10th ed.). Pearson.** — Chapter 5 covers cash-and-carry arbitrage in the futures context; the math translates directly to perp funding once you replace "expiration premium" with "cumulative funding".
2. **Bybit. "Funding Rate Mechanism."** https://www.bybit.com/en/help-center/article/Introduction-to-Funding-Rate — Exchange-specific documentation on Bybit's funding calculation (premium index TWAP, interest rate component, ±0.05% clamp).
3. **Binance. "Funding Rate History."** https://www.binance.com/en/futures/funding-history — Historical funding data for cross-venue comparison; useful for backtests of cash-and-carry strategies.
4. **Hayes, A. (2017). "A Cost of Production Model for Bitcoin." *Telematics and Informatics*.** https://www.sciencedirect.com/science/article/abs/pii/S0736585315300083 — Macro perspective on what drives crypto funding (capital flows, leverage demand) — useful for understanding why funding regimes persist.
5. **Coinbase Institutional. "Cash-and-Carry Trade Mechanics." (Quarterly research note, 2023).** https://www.coinbase.com/institutional/research-insights — Practitioner-facing primer on cash-and-carry mechanics on regulated venues and the practical risks (counterparty, settlement).
6. **Liu, Y., & Tsyvinski, A. (2021). "Risks and Returns of Cryptocurrency." *Review of Financial Studies*.** https://academic.oup.com/rfs/article/34/6/2689/5917119 — Peer-reviewed empirical work documenting funding-rate dynamics and their relationship to crypto returns; useful for sizing the historical basis-trade edge.
