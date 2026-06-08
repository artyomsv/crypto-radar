# Options Mechanics in Crypto

> Crypto options are 95% Deribit's product. They behave like equity options with a few important differences: cash-settled inverse contracts, native crypto margin, 24/7 markets, and concentrated open interest around BTC/ETH.

## Definition

A crypto option is a right (not obligation) to buy (call) or sell (put) a quantity of an underlying crypto asset at a specified strike, on or before a specified expiry, for a premium paid up front. Standard European-style exercise for cash-settled crypto options (no early exercise); American-style for some smaller venues.

The first-order math is identical to equity options: pricing is via Black-Scholes-Merton or local-vol/stochastic-vol extensions, the Greeks (delta, gamma, vega, theta, rho) carry the same interpretations, the put-call parity relationship holds modulo dividends being zero.

Where crypto options *differ* from equities is in the contract spec, the venue concentration, and the underlying behavior:

- **Cash-settled, not physically-settled.** A BTC call exercised in the money pays out `max(S - K, 0)` in USDC or BTC at the index price at expiry. No deliverable underlying. This is universal at Deribit, CME, Bybit options.
- **Inverse vs linear contracts.** A "linear" BTC option pays in USD/USDC and is what most retail traders see. An "inverse" BTC option (Deribit's flagship) pays in BTC — so a long BTC call's PnL is denominated in BTC, with all the non-linearity that implies (your gains are in the asset whose price moved).
- **24/7 markets.** No daily expiry session, no auction. Settlement happens at fixed UTC times (08:00 UTC for Deribit's daily/weekly/monthly expiries).
- **Concentrated expiry calendar.** The largest open interest sits in the last-Friday-of-the-month expiries. Quarterly expiries (end-March, end-June, etc.) carry institutional hedging flow.

## Venue concentration — Deribit

By the end of 2024, **Deribit controlled about 85% of global crypto options volume**, and held above 85% through most of 2025. Coinbase acquired Deribit for $2.9B in cash and stock, announced May 2025, closed August 2025 — Deribit now operates as a Coinbase subsidiary under the "Deribit by Coinbase" brand. ([CoinLaw options market stats](https://coinlaw.io/options-market-in-crypto-statistics/); [CoinDesk on the Coinbase-Deribit deal](https://www.coindesk.com/business/2025/05/08/coinbase-buys-deribit-for-usd2-9b))

Two things changed in 2025:

1. **BlackRock IBIT options surpassed Deribit's BTC open interest** for the first time in Q3 2025 ($27.61B vs $26.9B in April 2026), shifting BTC options dominance from offshore to a regulated US venue.
2. **ETH options remain >90% Deribit** with no regulated US ETF options competitor. For ETH-and-below market cap names, Deribit is essentially the sole liquidity center.

The implication for any signal that reads options market data (IV, skew, term structure): the Deribit API is the source of truth for ETH and altcoin options, and increasingly *also* needs IBIT options data for BTC complete-picture analysis.

## Greeks — the working set

For a signal-side consumer (not a market maker), the relevant Greeks are:

- **Delta.** Probability-weighted directional exposure. A 25-delta call has roughly 25% probability of finishing ITM; for traders, a useful threshold for "near OTM but not deep" strikes.
- **Gamma.** How fast delta changes with the underlying. Highest at ATM strikes, near expiry. Market-maker gamma positioning is a microstructure flow signal — large negative gamma at a strike means hedging flow amplifies moves in that direction.
- **Vega.** Sensitivity to implied volatility. The signal-side reading: if vega-rich strikes are bid, the market expects IV expansion (vol up).
- **Theta.** Time decay. For us, mostly relevant in understanding option seller positioning.

Natenberg's *Option Volatility & Pricing* and Sinclair's *Volatility Trading* are the canonical practitioner references; both treated in `09-sources/01-books.md`.

## When options data matters for signal generation

- **25-delta skew (puts vs calls).** The persistent premium of OTM puts over OTM calls quantifies tail-risk pricing. When 1-week 25d-put IV widens sharply over 25d-call IV, the market is paying for downside protection — a leading indicator before sharp risk-off moves.
- **Term-structure inversion.** Front-end IV above back-end IV (a contango → backwardation flip) is a stress signal historically associated with regime breaks.
- **Realized-vol vs implied-vol gap (RV-IV).** When realized vol persistently undershoots implied vol, option sellers (call writers, put writers) collect premium with positive expectancy — option *buyers* face a structural cost.

## What we do today

Crypto options data is **not currently integrated into the signal pipeline**. The market-data, derivatives, and signal services do not consume Deribit. This is a deliberate sequencing choice — the 6-dimension scorer covers funding, OI, long/short, and liquidations on the perp side, which already gives a reasonable read on positioning without the added complexity of options surfaces.

Trade execution does run on Bybit's `OPTION` category implicitly (Bybit options are listed alongside perps in the V5 API), but we *only* execute USDT-perp orders. The `BybitV5RestClient.java` `categoryFor(symbol)` resolver maps everything we trade to `linear` — options would require a separate code path.

This is a known forward-looking opportunity:
- A `Volatility` dimension (Deribit BTC/ETH 25d-skew, term-structure slope, RV-IV gap) would add a non-redundant signal to the existing six dimensions.
- Implementation effort: a new microservice on a low-frequency (5-minute) poll against the Deribit public API, no auth needed for reference data, then a new dimension scorer in `signal-service`.

## When options data fails as a signal

- **Around very thin altcoin options.** Outside BTC/ETH, the Deribit options book is too thin to extract reliable IV from for short-dated strikes. Skew computed from 5-quote-deep books is noise.
- **Around large monthly/quarterly expiries.** Pin risk: spot can be drawn toward strikes with high open interest as MMs hedge gamma into the close. The "max pain" hypothesis is overstated in academic studies but real for liquid expiries.
- **During Deribit-specific outages.** When Deribit goes down (rare but happened in late 2024), the entire crypto options reference disappears for hours. Any signal depending on a fresh skew read needs a graceful staleness handler.

## Reading list

1. [CoinLaw — Crypto Options Market Stats 2026](https://coinlaw.io/options-market-in-crypto-statistics/) — sourced market-share data, Deribit dominance, IBIT options growth.
2. [CoinDesk — Coinbase buys Deribit for $2.9B](https://www.coindesk.com/business/2025/05/08/coinbase-buys-deribit-for-usd2-9b) — deal context and venue ownership picture.
3. [Deribit — BTC Options metrics dashboard](https://www.deribit.com/statistics/BTC/metrics/options) — live IV, open interest, expiries.
4. [CoinGlass 2025 Crypto Derivatives Market Annual Report](https://www.coinglass.com/learn/2025-annual-report-en) — cross-venue derivatives statistics including options.
5. [CoinMarketCap Academy — Bitcoin Options Overtake Futures](https://coinmarketcap.com/academy/article/bitcoin-options-overtake-futures-as-institutions-favor-volatility-strategies) — institutional adoption narrative; useful for framing options data as a leading indicator.
6. Natenberg, *Option Volatility & Pricing* — canonical practitioner reference; see `09-sources/01-books.md`.
7. Sinclair, *Volatility Trading* — modern treatment of vol-as-asset; see `09-sources/01-books.md`.
