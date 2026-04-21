package com.cryptoradar.execution.testutil;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Defence-in-depth check that refuses to run @BeforeEach cleanup logic
 * against the live dev database. Tests in this module call
 * {@code accountRepo.deleteAll()} / {@code tradeRepo.deleteAll()} /
 * {@code eventRepo.deleteAll()} to reset state between test methods — if
 * the JDBC URL is pointed at the running dev Postgres, those deletes wipe
 * the developer's registered exchange accounts and open trades.
 *
 * <p>This happened once (2026-04-21): a hardcoded
 * {@code jdbc:postgresql://localhost:31432/marketdata} override in each
 * test profile meant running {@code mvnd test} while dev services were up
 * deleted the active Bybit account mid-session. The {@code application.properties}
 * under {@code src/test/resources} now forces Dev Services on and removes
 * any URL override; this guard catches anyone re-introducing a hardcoded
 * URL in a test profile by failing the test class before cleanup can run.
 *
 * <p>Call from {@code @BeforeAll} in every {@code @QuarkusTest} that performs
 * destructive DB setup.
 */
public final class LiveDbGuard {

    /** The live dev DB name — any test URL ending with this exact suffix is rejected. */
    private static final String LIVE_DB_SUFFIX = "/marketdata";

    private LiveDbGuard() {}

    /**
     * Throws if Quarkus resolved a JDBC URL whose path ends in the live dev
     * database name. Test DBs should use a distinct suffix such as
     * {@code /marketdata_test} on the same TimescaleDB container.
     */
    public static void assertNotLiveDb() {
        String url = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.datasource.jdbc.url", String.class)
                .orElse("");
        if (url.endsWith(LIVE_DB_SUFFIX)) {
            throw new IllegalStateException(
                    "Refusing to run tests against the live dev DB: " + url
                    + ". Tests must use /marketdata_test (or another *_test DB) "
                    + "— see src/test/resources/application.properties."
            );
        }
    }
}
