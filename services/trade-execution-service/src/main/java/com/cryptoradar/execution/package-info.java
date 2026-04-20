/**
 * Trade execution service root package.
 *
 * <p>Mirrors trading signals from {@code signal-service} to real orders on
 * external exchanges (Bybit V5 perp futures in phase 1). Consumes the
 * existing Redis {@code crypto:signals} channel; manages positions natively
 * on-exchange (TP/SL + trailing stop); persists execution state locally.
 */
package com.cryptoradar.execution;
