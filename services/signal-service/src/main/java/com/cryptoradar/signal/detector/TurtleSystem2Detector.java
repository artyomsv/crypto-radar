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
 * Turtle System 2: the slower 55-day breakout entry with no loser-filter
 * (always taken). Reverse exit is the 20-day channel (managed downstream);
 * stop is 2N; pyramiding-eligible on the execution side (Plan 2).
 */
@ApplicationScoped
public class TurtleSystem2Detector implements TradeSetupDetector {

    static final String NAME = "turtle-s2";
    private static final double TARGET_N_MULTIPLE = 20.0;
    private static final int MECHANICAL_ALIGNMENT = 60;

    @ConfigProperty(name = "turtle.s2.enabled", defaultValue = "true")
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

        DonchianMath.Breakout dir = DonchianMath.breakoutDirection(price, snap.high55(), snap.low55());
        if (dir == DonchianMath.Breakout.NONE) return Optional.empty();

        return Optional.of(BreakoutSetups.build(NAME, context.symbol(), price, snap.n(),
                dir == DonchianMath.Breakout.LONG, DonchianMath.STOP_MULTIPLE_2N, TARGET_N_MULTIPLE,
                MECHANICAL_ALIGNMENT,
                List.of(String.format("Turtle S2 55-day %s breakout (high55=%.4f low55=%.4f N=%.4f)",
                        dir, snap.high55(), snap.low55(), snap.n()),
                        "Operative exit = reverse 20-day Donchian monitor; TP is a 20N backstop"),
                Instant.now()));
    }
}
