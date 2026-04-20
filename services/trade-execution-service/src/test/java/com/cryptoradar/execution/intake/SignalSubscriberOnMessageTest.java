package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Behaviour-level test for {@link SignalSubscriber#onMessage(String)}. Drives the
 * subscriber directly with synthetic JSON payloads — no Redis connection required.
 * Validates the three short-circuit paths that must NOT touch the order placer:
 * malformed JSON, non-actionable labels, and no-accounts-configured.
 */
@QuarkusTest
@TestProfile(SignalSubscriberOnMessageTest.Profile.class)
class SignalSubscriberOnMessageTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String keyB64 = Base64.getEncoder().encodeToString(k);
            return Map.ofEntries(
                    Map.entry("bybit.rest-base-override.DEMO", "http://localhost:38103"),
                    Map.entry("bybit.rest-base-override.MAINNET", "http://localhost:38103"),
                    Map.entry("execution.master-key", keyB64),
                    Map.entry("execution.master-key-prev", keyB64),
                    Map.entry("execution.mainnet.enabled", "false"),
                    // Point Redis at a loopback port that nothing is listening on —
                    // we never exercise the subscribe path in this test.
                    Map.entry("quarkus.redis.hosts", "redis://localhost:38199"),
                    // Use the running TimescaleDB container that already has execution-init.sql applied.
                    Map.entry("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:31432/marketdata"),
                    Map.entry("quarkus.datasource.username", "cryptoradar"),
                    Map.entry("quarkus.datasource.password", "cryptoradar_ts_pass")
            );
        }
    }

    @Inject SignalSubscriber subscriber;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    @BeforeEach
    @Transactional
    void cleanDb() {
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    @Transactional
    void malformedJsonDoesNotThrow() {
        subscriber.onMessage("not json");
        subscriber.onMessage("{malformed");
        subscriber.onMessage("{}");
        subscriber.onMessage("");
        // No accounts, no trades — just verifying these calls don't escape.
        assertEquals(0, tradeRepo.listAll().size());
    }

    @Test
    @Transactional
    void nonActionableSignalIsIgnored() {
        subscriber.onMessage(
                "{\"type\":\"alert\",\"data\":{\"signal\":{\"symbol\":\"BTCUSDT\",\"signal\":\"NEUTRAL\"}}}");
        subscriber.onMessage(
                "{\"type\":\"alert\",\"data\":{\"signal\":{\"symbol\":\"BTCUSDT\",\"signal\":\"BUY\"}}}");
        subscriber.onMessage(
                "{\"type\":\"alert\",\"data\":{\"signal\":{\"symbol\":\"BTCUSDT\",\"signal\":\"SELL\"}}}");
        assertEquals(0, tradeRepo.listAll().size());
    }

    @Test
    @Transactional
    void noAccountsConfiguredDoesNothing() {
        // STRONG_BUY is actionable, but without any exchange account rows there's nothing to route to.
        subscriber.onMessage(
                "{\"type\":\"alert\",\"data\":{\"signal\":{\"symbol\":\"BTCUSDT\",\"signal\":\"STRONG_BUY\"}}}");
        assertEquals(0, tradeRepo.listAll().size());
    }
}
