package com.cryptoradar.signal.model;

/**
 * Global market regime inferred from BTC price action. Consumed by the
 * {@code SignalEngine} to modulate its emission thresholds so counter-trend
 * signals need stronger evidence to fire than trend-aligned ones.
 *
 * <ul>
 *   <li>{@link #BULL} — BTC is in an uptrend. Raise SELL thresholds; don't
 *       short a bull market unless the setup is textbook.</li>
 *   <li>{@link #BEAR} — BTC is in a downtrend. Raise BUY thresholds; don't
 *       buy falling knives.</li>
 *   <li>{@link #CHOP} — no clear trend. Default thresholds both directions.</li>
 *   <li>{@link #UNKNOWN} — data unavailable or insufficient history. Treated
 *       like {@link #CHOP} by the engine (safest neutral stance).</li>
 * </ul>
 */
public enum MarketRegime {
    BULL,
    BEAR,
    CHOP,
    UNKNOWN
}
