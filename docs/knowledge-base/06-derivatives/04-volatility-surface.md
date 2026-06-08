# Volatility Surface (Smile, Skew, Crypto-Specific Shape)

> The volatility surface is the 3D map of implied vol against strike and time-to-expiry. The two slices that matter are the smile (vol-vs-strike at fixed expiry) and the term structure (vol-vs-expiry at fixed moneyness). Crypto's surface differs structurally from equity's — knowing how to read those differences is a tradeable edge.

## Definition

If the world followed Black–Scholes assumptions exactly, the IV surface would be a horizontal plane: a single number, same for every strike, every expiry. Instead, real surfaces show consistent deformation:

- **Smile / smirk:** A U-shaped or skewed cross-section when IV is plotted against strike (or log-moneyness `k = ln(K/S)`) at a fixed expiry. OTM puts trade at higher IV than ATM in equity indices — this is the **negative skew** of equity-index options, attributable to crash-fear premium.
- **Term structure:** A cross-section of ATM IV against time-to-expiry. Typically upward-sloping (contango) when forward vol expectations exceed near-term, downward-sloping (backwardation) when near-term realized stress is high.
- **Surface:** The full 2D function `σ(K, T)`. Practitioners parametrize it via SVI, SABR, or non-parametric kernel smoothers.

### Equity vs crypto skew

In SPX, OTM 25-delta puts trade ~5–15 vol points above ATM. The skew is steep and persistent, reflecting hedging demand for downside protection.

Crypto skew is **structurally different and more symmetric**:

- The downside premium is smaller in absolute terms — BTC 25Δ puts rarely exceed ATM by more than 5 vol points outside of crash regimes.
- The **upside also commands a premium** at certain points in the cycle — leveraged longs paying up for OTM calls during bull-market FOMO is a recurring pattern. The smile becomes a near-symmetric "smile" rather than a "smirk."
- Skew flips sign more often than equity skew. BTC's 25Δ-put vs 25Δ-call differential (the "risk reversal") oscillates between negative (put-heavy fear) and positive (call-heavy euphoria) on multi-week cycles.

Carr–Wu (2009) decompose the equity skew into a jump-fear component and a stochastic-vol component. In crypto the jump component is larger (jumps are more frequent and bigger) but the directional asymmetry is weaker (jumps can go either way — exchange hack vs ETF approval). The result: bigger absolute IV everywhere, but flatter skew than equity.

### Quoting conventions

- **Delta-quoted:** 25Δ-put, 50Δ (≈ATM), 25Δ-call. Standard for OTC FX-style markets.
- **Strike-quoted:** vol for a specific dollar strike. Standard for exchange-listed BTC options.
- **Moneyness-quoted:** vol vs `ln(K/F)` where F is the forward. Cleanest for cross-expiry comparisons.

Three derived numbers traders watch:
- **Risk reversal (RR):** `IV(25Δ-call) − IV(25Δ-put)`. Direction of skew.
- **Butterfly (Fly):** `0.5 × (IV(25Δ-call) + IV(25Δ-put)) − IV(ATM)`. Convexity of smile.
- **Variance swap rate:** model-free aggregate IV across the strike spectrum. The basis for VIX-style indices including DVOL.

## When it works

- **Skew as a tail-risk thermometer.** Equity 25Δ-put skew widening before a sell-off is documented; crypto 25Δ-put skew widening alongside negative funding has the same diagnostic value.
- **Trading skew directly.** A risk reversal expresses a directional view without naked delta. Buying the call, selling the put, delta-hedging — this is a pure skew bet, profitable if realized skew flattens (price up + IV down).
- **Surface arbitrage.** Strikes that violate static no-arb conditions (butterfly negative, calendar negative) signal mis-priced contracts. Liquidity-providers' inventories sometimes leave these on the screen for minutes.
- **Cross-asset signals.** BTC put-skew steepening often precedes alt-coin drawdowns by 1–3 days. The signal isn't reliable enough to trade naked, but it's a useful regime input.

## When it fails

- **Smile parametrizations are model-dependent.** SVI fits the surface well in calm regimes and fails in stress. Don't trade smile arbitrage off a single model's residuals.
- **Wing illiquidity.** The interesting tails (5Δ wings) have wide spreads and stale marks. The "skew" you see on a screen might not be a tradeable skew.
- **Listing-related distortions.** When Deribit adds a new expiry, the early days of that expiry's quotes are dominated by market-maker inventory choices, not collective expectations.
- **Crypto correlation breakdowns.** Equity-skew intuition that "skew widens before drops" assumes one-way crash risk. In crypto, the upside also pops — a "wide smile" can precede a melt-up just as easily as a melt-down.

## What we do today (in projectr-x)

`options-service` ingests the full chain but does not yet compute surface analytics. Each `OptionSnapshot` row carries `strike`, `expiry`, `markIv`, `delta` — enough to build SVI fits or risk-reversal series, but no scheduler does it.

The `OpportunityScorer.pickStrangle` selects only the ATM legs (nearest call + nearest put to spot), which is a single point on the surface. It ignores the smile entirely. This is an acknowledged simplification — when the project wants directional skew signals, the natural place to add them is:

1. New `SurfaceMetric` model (`riskReversal25d`, `butterfly25d`, `atmTermSlope`) computed per snapshot.
2. Persistence to `option_surface_metrics` hypertable.
3. Feed `riskReversal25d` into `derivatives-service` as an additional dimension input alongside funding, OI, and L/S.

The crypto-specific quirk that BTC skew flips sign is exactly the kind of high-information feature the dimension scoring stack should consume — but only after we have ≥4 weeks of clean surface data to validate the signal before plugging it in.

## Sources

1. **Carr, Wu (2003), "What Type of Process Underlies Options? A Simple Robust Test."** *Journal of Finance* 58(6). https://www.jstor.org/stable/3648193 — Methodology for decomposing surface into diffusion + jump components. Cited heavily for the "crypto jumps differently" thesis.
2. **Carr, Wu (2009), "Variance Risk Premiums."** *Review of Financial Studies* 22(3). https://www.jstor.org/stable/30225713 — Surface-level VRP across asset classes. Foundational.
3. **Gatheral, *The Volatility Surface: A Practitioner's Guide* (2006).** The standard text. Chapter 1 (smile dynamics), Chapter 3 (SVI parameterization), Chapter 7 (jumps and skew).
4. **Alexander, Imeraj (2023), "Inverse Options in a Black–Scholes World."** https://arxiv.org/abs/2107.12035 — Treatment of crypto-specific options (inverse-quoted BTC contracts on Deribit) where the standard surface intuition breaks.
5. **Saef, Wang, Aste (2022), "Regime-based implied volatility model for cryptocurrency markets."** https://arxiv.org/abs/2208.08585 — Empirical analysis showing crypto skew is regime-dependent and more symmetric than equity.
6. **Deribit Insights — Risk reversal articles.** https://insights.deribit.com/ — Practitioner commentary on crypto RR dynamics; the source for the "BTC skew flips sign" observation.
