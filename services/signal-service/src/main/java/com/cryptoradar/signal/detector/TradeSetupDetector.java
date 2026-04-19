package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;

import java.util.Optional;

/**
 * A rule-based trade setup detector. Receives a fully-populated
 * {@link MarketContext} and returns either a concrete {@link TradeSetup}
 * or {@link Optional#empty()} if its preconditions are not met.
 *
 * <p>Design rules for implementations:
 * <ul>
 *   <li><b>Fail fast.</b> Check the most disqualifying condition first and
 *       bail immediately on failure — don't partially score marginal
 *       setups. Quality over quantity is the whole point.</li>
 *   <li><b>Pure.</b> No I/O, no side effects, no caches. The engine passes
 *       everything via the context so detectors stay unit-testable.</li>
 *   <li><b>One setup, one direction.</b> A single detector evaluation
 *       returns at most one setup for a given symbol. If the detector
 *       could fire both LONG and SHORT simultaneously, they should be
 *       split into two detector classes so each can be measured
 *       independently.</li>
 *   <li><b>Explain yourself.</b> Populate {@link TradeSetup#reasons()}
 *       with the preconditions that fired so after-the-fact analysis can
 *       correlate reason patterns with realized outcomes.</li>
 * </ul>
 */
public interface TradeSetupDetector {

    /** Stable identifier used in the {@code strategy} column of outcomes. */
    String name();

    /** Evaluates the context and returns a setup if preconditions are met. */
    Optional<TradeSetup> detect(MarketContext context);
}
