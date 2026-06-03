/**
 * Centralised explanation text for every field on the options watchlist.
 * Keyed by short id so card subcomponents reference identical wording.
 *
 * Keep each entry ≤ 2 sentences — native browser tooltips truncate
 * gracelessly past ~200 chars.
 */
export const OPTION_TOOLTIPS = {
    strategy:
        'Straddle = same strike for call + put. Strangle = different OTM strikes. Both profit when spot moves a lot in either direction.',
    dte:
        'Time remaining until expiry. Bybit options settle at 08:00 UTC on the expiry date. Shorter DTE = faster theta decay.',
    verdict:
        'STRONG BUY needs confidence ≥85 and signal overlay ≥60. BUY: confidence ≥70. WAIT: 50-69. SKIP: below 50.',
    confidence:
        'Composite score 0-100. 60% from IV/RV gap (cheap options) + 40% from signal-engine overlay. Threshold to fire is 70.',
    detected:
        'When the scorer first flagged this setup. The scorer re-detects every 60s but rows dedupe within a 1-hour cooldown.',
    callStrike:
        'Strike price of the call leg. The position becomes profitable above (callStrike + total premium paid).',
    putStrike:
        'Strike price of the put leg. The position becomes profitable below (putStrike − total premium paid).',
    premium:
        'Total USDT cost to buy both legs (call ask + put ask). This is the maximum loss if spot stays between the breakevens at expiry.',
    premiumPct:
        'Premium expressed as percentage of underlying spot. Lets you compare cost across BTC, ETH, DOGE on the same scale.',
    breakeven:
        'How far spot must move (either direction) for the trade to break even at expiry. Smaller is better — less work for the trade thesis.',
    impliedDaily:
        'Implied daily $ move derived from IV ATM. Roughly IV ÷ √365 × spot. Compare to break-even to see if vol pricing offers enough room.',
    ivAtm:
        'Implied volatility at the money — Bybit\'s annualised vol expectation derived from option prices. Higher IV = more expensive options.',
    rv7d:
        'Realised volatility over the last 7 days. Standard deviation of daily log-returns, annualised by √365.',
    rv14d:
        'Realised volatility over the last 14 days. Same calc as RV 7d but smoother (longer lookback). Used as the reference for the IV/RV gap.',
    ivRvGap:
        'RV14 − IV ATM. Positive = realised vol exceeds implied = options cheap vs actual movement = strangle expected payoff positive.',
    signalOverlay:
        '0-100 score from recent signal-engine activity for this underlying. Mix of signal density (last 6h), alignment, and |R| of recent outcomes.',
    netDelta:
        'Sum of call delta + put delta. Near zero for a balanced straddle (direction-neutral). Positive = bullish exposure, negative = bearish.',
    netGamma:
        'Rate of change of delta per $1 spot move. Higher gamma means the position picks up directionality fast on big moves.',
    thetaPerDay:
        'Daily $ decay cost of the position. Theta works against long premium — this is the rent you pay per day to hold the strangle.',
    netVega:
        'Profit per 1 percentage-point IV expansion. Positive vega = position gains if implied vol rises after entry.',
    totalOi:
        'Sum of open-interest across both legs. Higher OI = more liquid market = tighter spreads on exit.',
    totalVol:
        'Sum of 24h trading volume across both legs. Confirms the contract is actively trading, not stale.',
    hitRate:
        'Historical win-rate for resolved opportunities in the same underlying + confidence bucket. Hidden when sample size is below 10.',
    why:
        'Auto-generated 1-sentence explanation of why this setup qualified. Only mentions factors backed by actual data.',
    spotPrice:
        'Underlying spot price — live (latest snapshot) when available, else the entry-time value.',
    livePremium:
        'Current cost of the strangle (call ask + put ask) using the latest leg snapshots. Diverges from entry premium when IV reprices.',
    liveConfidence:
        'Re-computed confidence using the latest IV/RV gap + signal overlay. Shows whether the original thesis still holds; drops below 50 hide the card.',
    liveIvRvGap:
        'Current RV14 − IV ATM gap. If the gap collapses (small or negative), options are no longer cheap relative to actual movement.',
    stale:
        'This setup no longer meets entry criteria — live confidence collapsed, spot moved outside the strike band, or expiry passed. Already-open positions remain valid; this only signals that fresh entry is no longer indicated.',
    entryVsLive:
        'Entry values are frozen at detection. Live values re-compute every refresh from the latest data. Big drift means the original thesis is breaking.',
} as const;

export type OptionTooltipKey = keyof typeof OPTION_TOOLTIPS;
