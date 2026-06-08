# Bitcoin Market Cycles

> Bitcoin has shown four roughly four-year cycles aligned with its programmatic halving events, but the causal claim — "halving drives the cycle" — is contested. The stock-to-flow model that formalized the claim has been comprehensively rejected on its own terms. What's left is a weaker but still useful regime-shape observation.

## Definition

Bitcoin's protocol halves the block subsidy roughly every 210,000 blocks (~four years). Halvings have occurred in November 2012, July 2016, May 2020, and April 2024. Each cut the per-block reward in half: 50 → 25 → 12.5 → 6.25 → 3.125 BTC.

The **four-year cycle** thesis observes that:

- Each halving has been followed within 6–18 months by a major bull peak (Dec 2013, Dec 2017, Nov 2021, expected 2025–26).
- The peak-to-trough drawdowns between cycles have been progressively shallower in percentage terms (~85% → ~84% → ~77%) and faster to recover.
- "Accumulation" phases lasting 12–24 months precede each halving, characterized by sub-200d-MA spot, dormant on-chain activity, capitulation in mining hashrate after halving.

The **stock-to-flow (S2F) model**, popularized by the pseudonymous "PlanB" in March 2019, attempted to formalize this into a price prediction: `Price = exp(α) × (Stock/Flow)^β`, with `Stock` = circulating supply and `Flow` = annual new issuance. The published fit produced an R² > 0.95, suggesting halvings should produce log-linear price jumps as the S2F ratio rises.

## The Coinmetrics critique

PlanB's S2F has been comprehensively dismantled, most cleanly by Coinmetrics' Nic Carter and the academic responses that followed. The core flaws:

1. **Spurious regression on non-stationary series.** Two trending time series produce high R² regardless of causal relationship. The original S2F regression did not test for cointegration; when proper unit-root tests are applied, the fit disappears.
2. **Look-ahead bias in the fit period.** PlanB's data window included Bitcoin's entire price history at fit time, but the model was presented as predictive. Out-of-sample tests since 2020 have been progressively worse — by mid-2022, predictions diverged from realized price by an order of magnitude.
3. **"PlanB invalidated" date.** The model's lower band was breached in mid-2022. Subsequent revisions ("S2FX," etc.) were ad-hoc curve-fits to maintain the narrative.
4. **Theoretical incoherence.** S2F's underlying analogy (gold, silver) ignores demand-side dynamics. Supply scarcity alone doesn't price an asset — it's the marginal buyer who does.

The academic literature is now broadly aligned: the four-year price pattern is real-but-confounded, S2F as a quantitative model is dead, and any claim of "X price by Y date based on the halving" is selling something.

## What's actually left

A weaker but defensible observation: **halvings change the marginal-seller equation**. Pre-halving, miners must sell ~900 BTC/day to cover operating costs at current power prices. Post-halving, that floor halves. With demand approximately constant (a heroic assumption), the supply-shock shows up over months as price firms. This is microstructure, not metaphysics. It is also overlaid with macro liquidity cycles — both 2017 and 2021 peaks coincided with maximum global central-bank balance-sheet expansion, not pure halving math.

For this project's purposes: BTC's 4-year cycle is **a regime-shape input, not a forecasting tool**. Use it to ask "are we in early-cycle accumulation or late-cycle euphoria?" — knowing that the answer affects appropriate position sizing, leverage, and risk thresholds — not to pin specific prices to specific dates.

## When it works

- **Coarse regime context.** Knowing "we're 18 months past halving" is a useful prior for "should I expect a euphoria phase next, or are we late?"
- **Drawdown depth expectations.** Bear-market drawdowns in BTC have historically been 70–85%. A strategy that's intolerant of 70% drawdowns has no business holding spot through a cycle bottom.
- **Mining-side seasonality.** Hashrate capitulation moments (mid-2018, mid-2022) have been within ~30% of the price low. The signal isn't fast enough to trade but is useful for position-sizing decisions.
- **Macro liquidity overlay.** Cycles align loosely with global M2 + Fed balance sheet expansions. The halving is one ingredient; cheap money is the other.

## When it fails

- **As a price predictor.** Stock-to-flow is dead. Any model claiming to forecast specific BTC prices from halving math is curve-fit.
- **As a "this time is different" excuse.** Cycle veterans burned in 2018 said "this time the institutional bid is structural" in 2021 and got drawn down 77% anyway. The pattern's recurrence cuts both ways.
- **For sub-cycle timing.** Halving math tells you nothing about whether next month is up or down. Short-horizon strategies that lean on cycle position are confusing a slow-moving prior for a fast-moving signal.
- **In post-ETF regimes.** The Jan 2024 spot ETF launch added a new structural-flow source (BlackRock IBIT, Fidelity FBTC, etc.) absent in prior cycles. Forward cycles may differ in shape from priors precisely because the marginal buyer/seller has changed.

## What we do today (in projectr-x)

Nothing explicit. `MarketRegimeService.java` classifies BTC into BULL/BEAR/CHOP/UNKNOWN from a 60-day daily window (50-day SMA + 7-day slope + 2% band). This is a **medium-horizon trend classifier**, not a cycle indicator — the look-back window is too short to capture 4-year structure.

We deliberately have not added "where are we in the 4-year cycle?" as a feature, for three reasons:

1. The signal is too slow for our 1-minute outcome evaluator and 15-minute regime refresh.
2. The post-2024-ETF regime introduces a structural break — the prior 3 cycles' shape is not necessarily indicative.
3. Adding a "cycle position" score risks confirmation bias for the user (and for any LLM-driven analyst pass) without adding actionable edge at the time-horizons we trade.

If a cycle-aware feature is added later, the right home is a new `MacroRegimeService` adjacent to `MarketRegimeService`, reading from on-chain data (block height since last halving) and not from price.

## Sources

1. **PlanB (2019), "Modeling Bitcoin's Value with Scarcity."** https://medium.com/@100trillionUSD/modeling-bitcoins-value-with-scarcity-91fa0fc03e25 — The original S2F article. Required reading to understand what the critique is rejecting.
2. **Nic Carter / Coinmetrics, "Falsifying Stock-to-Flow as a Model of Bitcoin Value" (Marcel Burger / Coinmetrics network).** https://medium.com/burgercrypto-com/falsifying-stock-to-flow-as-a-model-of-bitcoin-value-b2d9e61f68a7 — Statistical rejection of S2F using cointegration / unit-root tests.
3. **Wheatley et al. (2018), "Are Bitcoin Bubbles Predictable?"** https://arxiv.org/abs/1803.05663 — Academic analysis of Bitcoin's cyclical bubble structure using LPPL models. Independent of halving math.
4. **Glassnode, "Bitcoin: A Decentralized Macro Asset."** https://insights.glassnode.com/the-week-onchain-week-23-2023/ — On-chain signals around cycle phases; clean treatment without S2F overclaiming.
5. **Coinmetrics "State of the Network" archive.** https://coinmetrics.io/insights/state-of-the-network/ — Ongoing data-driven analysis of cycle markers (active addresses, realized cap, MVRV).
6. **Lopp (2024), "Halving Effects: Looking Back at 16 Years of Bitcoin."** https://blog.lopp.net/why-bitcoin-halving-matters-and-why-it-doesn-t/ — Practitioner summary of what halvings empirically do (mining economics) vs what they don't (predict price).
