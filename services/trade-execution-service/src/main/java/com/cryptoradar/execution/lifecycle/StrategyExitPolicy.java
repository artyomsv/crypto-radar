package com.cryptoradar.execution.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which strategies are "long-horizon" (multi-day
 * Turtle/Donchian breakouts). These are kept out of the intraday
 * StagnationMonitor + TrailMirror, exempted from the alignment-floor gate, and
 * recognised by the DonchianExitMonitor + mutual-exclusion guard.
 */
@ApplicationScoped
public class StrategyExitPolicy {

    @ConfigProperty(name = "execution.long-horizon-strategies",
            defaultValue = "donchian,turtle-s1,turtle-s2")
    String longHorizonCsv;

    public boolean isLongHorizon(String strategy) {
        if (strategy == null) return false;
        return parse().contains(strategy);
    }

    private Set<String> parse() {
        return Arrays.stream(longHorizonCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
