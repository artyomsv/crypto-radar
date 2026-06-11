package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turtle System 1: 20-day breakout entry with the original loser-filter — a
 * new breakout is skipped when the last closed {@code turtle-s1} trade for the
 * symbol was a winner (forces participation in the breakouts after a failed
 * one). Reverse exit is the 10-day channel (managed downstream); stop is 2N;
 * pyramiding-eligible on the execution side (Plan 2).
 */
@ApplicationScoped
public class TurtleSystem1Detector implements TradeSetupDetector {

    static final String NAME = "turtle-s1";

    @ConfigProperty(name = "turtle.s1.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradeSetup> detect(MarketContext context) {
        if (!enabled) return Optional.empty();
        DonchianSnapshot snap = context.donchian();
        Double price = context.currentPrice();
        if (snap == null || price == null) return Optional.empty();

        DonchianMath.Breakout dir = DonchianMath.breakoutDirection(price, snap.high20(), snap.low20());
        if (dir == DonchianMath.Breakout.NONE) return Optional.empty();
        // Loser-filter: suppress the entry when the prior S1 breakout won.
        if (snap.lastS1BreakoutWasWinner()) return Optional.empty();

        return Optional.of(BreakoutSetups.build(new BreakoutSetups.BreakoutSpec(
                NAME, context.symbol(), price, snap.n(),
                dir == DonchianMath.Breakout.LONG,
                List.of(String.format("Turtle S1 20-day %s breakout (high20=%.4f low20=%.4f N=%.4f)",
                        dir, snap.high20(), snap.low20(), snap.n()),
                        "Loser-filter passed (last S1 breakout was not a winner)",
                        "Operative exit = reverse 10-day Donchian monitor; TP is a 20N backstop"),
                Instant.now())));
    }
}
