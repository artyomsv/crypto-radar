package com.cryptoradar.execution.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which strategies are long-horizon (multi-day
 * Turtle/Donchian breakouts). Currently consumed by {@code StagnationMonitor}
 * and {@code TrailMirror} to skip the intraday stagnation/trail exits. Planned
 * consumers (later in this feature): the Donchian exit monitor, the
 * alignment-floor exemption, and the mutual-exclusion guard.
 */
@ApplicationScoped
public class StrategyExitPolicy {

    private static final String DEFAULT_LONG_HORIZON_CSV = "donchian,turtle-s1,turtle-s2";

    @ConfigProperty(name = "execution.long-horizon-strategies",
            defaultValue = DEFAULT_LONG_HORIZON_CSV)
    String longHorizonCsv;

    // Lazily computed from longHorizonCsv on first call. Not @PostConstruct:
    // unit tests set longHorizonCsv directly on a plain instance (no CDI), so
    // @PostConstruct would never run and parse() would NPE on a null CSV.
    private volatile Set<String> cached;

    public boolean isLongHorizon(String strategy) {
        if (strategy == null) return false;
        Set<String> set = cached;
        if (set == null) {
            set = parse();
            cached = set;
        }
        return set.contains(strategy);
    }

    private Set<String> parse() {
        return Arrays.stream(longHorizonCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
