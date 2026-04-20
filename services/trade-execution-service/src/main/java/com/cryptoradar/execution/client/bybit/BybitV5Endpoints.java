package com.cryptoradar.execution.client.bybit;

/**
 * Base URLs per Bybit environment. Picked at call time by the REST client and
 * WS client from the {@code exchange_accounts.environment} column.
 */
public final class BybitV5Endpoints {

    public static final String REST_DEMO = "https://api-demo.bybit.com";
    public static final String REST_MAINNET = "https://api.bybit.com";
    public static final String WS_DEMO = "wss://stream-demo.bybit.com/v5/private";
    public static final String WS_MAINNET = "wss://stream.bybit.com/v5/private";

    public static String restBaseFor(String environment) {
        return switch (environment) {
            case "DEMO" -> REST_DEMO;
            case "MAINNET" -> REST_MAINNET;
            default -> throw new IllegalArgumentException("unknown environment: " + environment);
        };
    }

    public static String wsPrivateFor(String environment) {
        return switch (environment) {
            case "DEMO" -> WS_DEMO;
            case "MAINNET" -> WS_MAINNET;
            default -> throw new IllegalArgumentException("unknown environment: " + environment);
        };
    }

    private BybitV5Endpoints() {}
}
