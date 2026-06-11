package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.lifecycle.StrategyExitPolicy;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MutualExclusionGuardTest {

    private final StrategyExitPolicy exitPolicy = new StrategyExitPolicy() {
        @Override public boolean isLongHorizon(String s) {
            return java.util.Set.of("turtle-s1", "turtle-s2", "donchian").contains(s);
        }
    };

    private MutualExclusionGuard guard(ExecutedTradeRepository repo) {
        return new MutualExclusionGuard(repo, exitPolicy);
    }

    private ExecutedTrade tradeWithStrategy(String strategy) {
        ExecutedTrade t = new ExecutedTrade();
        t.setStrategy(strategy);
        return t;
    }

    @Test
    void blocksWhenAnOpenBreakoutTradeHoldsSymbolDirection() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(1L, "BTCUSDT", "LONG"))
                .thenReturn(Optional.of(tradeWithStrategy("turtle-s1")));
        assertTrue(guard(repo).isBlocked(1L, "BTCUSDT", "LONG"));
    }

    @Test
    void allowsWhenExistingOpenTradeIsNotBreakoutFamily() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(1L, "BTCUSDT", "LONG"))
                .thenReturn(Optional.of(tradeWithStrategy("trend-continuation")));
        assertFalse(guard(repo).isBlocked(1L, "BTCUSDT", "LONG"));
    }

    @Test
    void allowsWhenNoOpenTrade() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        assertFalse(guard(repo).isBlocked(1L, "ETHUSDT", "SHORT"));
    }

    @Test
    void failOpenOnQueryError() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        assertFalse(guard(repo).isBlocked(1L, "ETHUSDT", "SHORT"));
    }
}
