package com.cryptoradar.execution.lifecycle;

/**
 * Pure decision logic for the native Turtle/Donchian exit. A LONG exits when
 * price breaches the reverse (low) channel; a SHORT exits when price breaches
 * the reverse (high) channel. Exit lookback is per strategy: donchian/turtle-s1
 * use the 10-day reverse channel, turtle-s2 uses the 20-day reverse channel.
 */
public final class DonchianExitDecision {

    private DonchianExitDecision() {}

    private static final int EXIT_LOOKBACK_FAST = 10; // donchian, turtle-s1
    private static final int EXIT_LOOKBACK_SLOW = 20; // turtle-s2

    public static int exitLookback(String strategy) {
        return switch (strategy) {
            case "donchian", "turtle-s1" -> EXIT_LOOKBACK_FAST;
            case "turtle-s2" -> EXIT_LOOKBACK_SLOW;
            default -> throw new IllegalArgumentException(
                    "no Donchian exit lookback for strategy: " + strategy);
        };
    }

    /** LONG exits at/below reverseLow; SHORT exits at/above reverseHigh. */
    public static boolean shouldExit(boolean isLong, double price,
                                     double reverseLow, double reverseHigh) {
        return isLong ? price <= reverseLow : price >= reverseHigh;
    }
}
