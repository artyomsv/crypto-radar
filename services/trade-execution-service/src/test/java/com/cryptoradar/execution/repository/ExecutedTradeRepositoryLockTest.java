package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.testutil.LiveDbGuard;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Covers the breakout mutual-exclusion advisory lock that serializes concurrent
 * detector-alert transactions on the same symbol+direction. The lock closes the
 * read-then-insert TOCTOU window that let two detector fires (e.g. turtle-s1 and
 * turtle-s2 for one symbol) both pass the mutual-exclusion check and double-open.
 */
@QuarkusTest
@TestProfile(ExecutedTradeRepositoryLockTest.Profile.class)
class ExecutedTradeRepositoryLockTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String keyB64 = Base64.getEncoder().encodeToString(k);
            return Map.of(
                    "execution.master-key", keyB64,
                    "execution.master-key-prev", keyB64,
                    "execution.mainnet.enabled", "false");
        }
    }

    @BeforeAll
    static void guardAgainstLiveDb() {
        LiveDbGuard.assertNotLiveDb();
    }

    @Inject ExecutedTradeRepository repo;

    @Test
    void breakoutLockKeyIsDeterministicPerSymbolDirection() {
        long a = ExecutedTradeRepository.breakoutLockKey(7L, "BTCUSDT", "SHORT");
        long b = ExecutedTradeRepository.breakoutLockKey(7L, "BTCUSDT", "SHORT");
        assertEquals(a, b, "same account+symbol+direction must map to the same lock key");
    }

    @Test
    void breakoutLockKeyDiffersAcrossDirectionAndSymbol() {
        long shortKey = ExecutedTradeRepository.breakoutLockKey(7L, "BTCUSDT", "SHORT");
        long longKey = ExecutedTradeRepository.breakoutLockKey(7L, "BTCUSDT", "LONG");
        long otherSymbol = ExecutedTradeRepository.breakoutLockKey(7L, "ETHUSDT", "SHORT");
        assertNotEquals(shortKey, longKey, "opposite directions must not share a lock key");
        assertNotEquals(shortKey, otherSymbol, "different symbols must not share a lock key");
    }

    @Test
    @Transactional
    void lockBreakoutKeyAcquiresWithoutError() {
        // Smoke: pg_advisory_xact_lock executes inside the active transaction and
        // auto-releases at commit. Re-acquiring the same key in the same tx is
        // re-entrant and must not block or throw.
        assertDoesNotThrow(() -> {
            repo.lockBreakoutKey(7L, "BTCUSDT", "SHORT");
            repo.lockBreakoutKey(7L, "BTCUSDT", "SHORT");
        });
    }
}
