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
 * Textbook Donchian channel breakout: enter on a 20-day high/low break, exit
 * (managed downstream) on the reverse 10-day channel. No entry filter, single
 * unit, no pyramiding — the simplest of the three breakout strategies and the
 * comparison baseline against the full Turtle variants.
 */
@ApplicationScoped
public class DonchianBreakoutDetector implements TradeSetupDetector {

    static final String NAME = "donchian";
    private static final double TARGET_N_MULTIPLE = 20.0;
    private static final int MECHANICAL_ALIGNMENT = 60;

    @ConfigProperty(name = "turtle.donchian.enabled", defaultValue = "true")
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

        return Optional.of(BreakoutSetups.build(NAME, context.symbol(), price, snap.n(),
                dir == DonchianMath.Breakout.LONG, DonchianMath.STOP_MULTIPLE_2N, TARGET_N_MULTIPLE,
                MECHANICAL_ALIGNMENT,
                List.of(String.format("Donchian 20-day %s breakout (high20=%.4f low20=%.4f N=%.4f)",
                        dir, snap.high20(), snap.low20(), snap.n()),
                        "Operative exit = reverse 10-day Donchian monitor; TP is a 20N backstop"),
                Instant.now()));
    }
}
