# Leverage and Liquidation

> Leverage is a multiplier on both edges and mistakes. Bybit's USDT-perp liquidation math is mechanical and unforgiving. Our 3x default exists because the spec sheet is friendly; the path-distribution is not.

## Definition

**Leverage** on a perpetual futures contract is the ratio of notional position value to posted margin. A 3x-leveraged $10,000 BTCUSDT long has $30,000 of notional exposure backed by $10,000 of margin. The implied funding-and-fee burden scales with the notional, not the margin. The price-movement-to-PnL conversion is `PnL = notional × (price_change_pct / 100)`.

**Initial margin** is the margin required to open the position, computed as `notional / leverage`. **Maintenance margin** is the lower threshold below which the position will be liquidated — typically a fraction of initial margin, varying by tier.

**Liquidation** happens when the position's *mark price* (Bybit-managed index, not the trader's preferred quote) reaches a price at which `equity = maintenance margin`. At that point Bybit auctions the position into the liquidity engine; the trader loses the position margin and may, in adverse conditions, owe more (this is what "insurance fund" exists to absorb).

**Bybit V5 USDT-perpetual liquidation price (isolated margin):**

- LONG: `liquidation_price = entry − (initial_margin − maintenance_margin) / position_size`
- SHORT: `liquidation_price = entry + (initial_margin − maintenance_margin) / position_size`

The **maintenance margin rate (MMR)** is tier-based — it increases as your position notional grows past tier boundaries. For BTCUSDT, position notional up to ~2M USDT is at MMR ~0.5%; the next tier kicks in at ~0.65%; further tiers up to 4–5%. For thinner alts, the tier table starts higher and ramps faster.

**Cross-margin vs isolated:** In isolated mode, only the position margin can be liquidated. In cross-margin mode, the entire account collateral backs every position, so a single losing position can be saved by other margin — but a catastrophic move can cascade-liquidate the whole book. projectr-x defaults to **isolated** for blast-radius reasons.

## When it works (i.e. when leverage is your friend)

- **High-conviction, short-hold trades with tight stops.** A 3x position with a 1% stop equals a 3% risk on the position margin. Within reason, that's still a small fraction of equity if position sizing is correct.
- **Capital-efficient.** Posting 1/3 the margin for a fixed exposure frees the rest of the account for diversification or other positions.
- **The stop is well-inside the liquidation price.** If your initial stop is 1.5% from entry and 3x leverage puts the liquidation at ~30% from entry, you have a comfortable cushion. The stop will fire first under any reasonable market move.

## When it fails (catastrophically)

- **Stop slips past liquidation.** A gap-down event, an exchange halt, or a stop-market order in thin liquidity can fill at the liquidation price — at which point the position is gone *and* the trader still pays slippage past the intended stop.
- **Maintenance margin tier kicks up.** If your position scales past a tier boundary, the MMR jumps and the liquidation price moves *toward* entry. The same trade size that was safe before the tier change may now be unsafe after.
- **Funding eats the cushion.** A 30-day held position on an extreme funding rate (e.g. -0.3% per 8h in a panic short squeeze) can lose 30%+ of margin to funding alone, *before* any price move. This is why long-hold leveraged carry strategies on crypto perps are dangerous.
- **Cross-margin cascade.** One losing position in cross-margin can pull margin from the others; if any of them are also near liquidation, the cascade is mechanical and fast.
- **High leverage with a wide stop.** 10x leverage with a 5% stop = 50% margin loss per stop-out. Two such stops back-to-back is a 75% drawdown. The 1% risk-per-trade discipline (`02-r-multiples-and-expectancy.md`) implicitly assumes leverage stays low enough that stop-out is a small fraction of equity — and the discipline breaks under 10x+ leverage.

## What we do today (in projectr-x)

`trade-execution-service` defaults to **3x leverage** on `ExchangeAccount.leverage` (configurable via SettingsPanel), **isolated margin** mode, with the following supporting guardrails:

- **Position sizing in margin units.** Notional = `(equity × riskPerTradePercent) / risk_per_unit_pct`, then divided by `leverage` to get posted margin. So at 1% risk per trade with a 1.5% risk distance and 3x leverage, posted margin ≈ `equity × 1% / 1.5% / 3 ≈ 0.22% of equity per position`. That's deliberately conservative.
- **Max concurrent positions cap.** `maxConcurrentPositions = 10` means total posted margin maxes at ~2.2% of equity across all open trades. Liquidation-of-everything is not a tail event we worry about at this configuration.
- **Daily loss kill switch.** `DailyPnlCalculator` halts new entries when realized daily PnL falls below `−maxDailyLossPercent` (default 7%, 10% on the DEMO account 297). This is the second-order defense against the path-distribution of leveraged trading: even if individual liquidations are unlikely, a string of losses on multiple correlated positions can still drain capital fast.
- **Conservative leverage default.** 3x was chosen because:
  1. Even on the wider 1.5% MIN_RISK_PCT, a 1% stop on 3x leverage = 3% margin loss per stop-out, which is still small.
  2. The liquidation distance at 3x is roughly `entry × (1 − 1/3 + maintenance_margin_rate)` ≈ 33% from entry on BTC — far outside any reasonable stop range.
  3. Funding carry at 3x is 3x the spot equivalent. At typical 0.01%/8h funding, 3x = 0.03%/8h. A trade held for 2 hours sees roughly 1/4 of that. Negligible.
- **Permission check on add-account.** The credential validator in `trade-execution-service` rejects API keys with withdraw permissions. If the key can withdraw, it's revoked at the venue and the account is not added. This is unrelated to leverage but related to blast radius.

The choice of 3x is also a **deliberate non-Kelly** decision. Kelly at our current 0.118R/signal expectancy would suggest a much smaller per-trade size (see `01-position-sizing-kelly.md`); 3x leverage with 1% risk-per-trade gives us 1% effective per-trade risk on equity, which is conservative-quarter-Kelly. Pushing leverage to 10x would push that toward full-Kelly territory and increase drawdown probability accordingly. We are explicitly choosing the slower-growth, lower-drawdown path until we have more data.

Code references:
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderPlacer.java` — leverage application, posted-margin computation.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/DailyPnlCalculator.java` — daily-loss kill switch, the macro guardrail.
- `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java` — calls into `POST /v5/position/set-leverage` and `POST /v5/position/switch-isolated` on account setup.
- `frontend/src/components/portfolio/SettingsPanel.tsx` — exposes leverage and `maxDailyLossPercent` as user-editable per account.

## Implementation sketch (room to improve)

1. **Pre-trade liquidation check.** Before placing an order, compute the implied liquidation price and reject if it's within 2× the initial stop distance. Acceptance: zero liquidations ever observed in production.
2. **Tier-aware sizing.** Read Bybit's current MMR tier table for the symbol on order placement; if the new position would push notional past a tier boundary, reject or split. Effort: 1 day. Important when scaling to larger accounts.
3. **Cross-margin opt-in flag.** Currently isolated-only. Cross is useful for advanced users who understand the cascade risk. Effort: 2 days, behind an explicit toggle that warns.

## Sources

1. [Bybit Help Center, "Liquidation Price (USDT Contract)"](https://www.bybit.com/en/help-center/article/Liquidation-Price-USDT-Contract) — canonical formula and worked examples for USDT-perp isolated/cross margin.
2. [Bybit Help Center, "Liquidation Price Calculation under Isolated Mode (Unified Trading Account)"](https://www.bybit.com/en/help-center/article/Liquidation-Price-Calculation-under-Isolated-Mode-Unified-Trading-Account) — UTA-specific liquidation math.
3. [Bybit Help Center, "Maintenance Margin (USDT Perpetual and Expiry Contracts)"](https://www.bybit.com/en/help-center/article/Maintenance-Margin-USDT-Contract) — the MMR tier table for USDT contracts, with maintenance margin deduction formulas.
4. [Bybit Help Center, "Trading Rules: Liquidation Process (Unified Trading Account)"](https://www.bybit.com/en/help-center/article/UTA-Trading-Rules) — the auction process and insurance fund mechanics.
5. [Bybit Help Center, "Understanding the Adjustment and Impact of the New Margin Calculation"](https://www.bybit.com/en/help-center/article/Understanding-the-Adjustment-and-Impact-of-the-New-Margin-Calculation) — recent (2024–2025) updates to UTA margin math.
6. [Bybit Help Center, "Order Cost (Perpetual and Expiry Contracts)"](https://www.bybit.com/en/help-center/article/Order-Cost-USDT-Contract) — initial margin and order-cost formulas with examples.
7. [Bybit, "USDC Perpetual Contracts FAQ"](https://www.bybit.com/en/help-center/article/USDC-Contract-FAQ) — companion doc for the USDC-collateralized variants we don't currently trade but might.
