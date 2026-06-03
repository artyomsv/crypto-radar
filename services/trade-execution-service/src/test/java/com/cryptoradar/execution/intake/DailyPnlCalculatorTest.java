package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.WalletV5;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit coverage of {@link DailyPnlCalculator}. Exercises the math
 * path, equity caching, and fail-open semantics. The Bybit + DB layers
 * are mocked so the test never reaches a real broker or Postgres.
 */
class DailyPnlCalculatorTest {

    private DailyPnlCalculator calc;
    private ExecutedTradeRepository tradeRepo;
    private BybitV5RestClient bybit;
    private ExchangeAccount account;

    @BeforeEach
    void setUp() {
        calc = new DailyPnlCalculator();
        tradeRepo = mock(ExecutedTradeRepository.class);
        bybit = mock(BybitV5RestClient.class);
        calc.tradeRepo = tradeRepo;
        calc.bybit = bybit;
        calc.executionSettings = new ExecutionSettingsService(null, null) {
            @Override
            public Snapshot snapshot() {
                return ExecutionSettingsService.Snapshot.defaults();
            }
        };
        account = mock(ExchangeAccount.class);
        when(account.getId()).thenReturn(1L);
        when(account.getEnvironment()).thenReturn("DEMO");
        when(account.getApiKeyEncrypted()).thenReturn("k");
        when(account.getApiSecretEncrypted()).thenReturn("s");
    }

    private void stubEquity(String totalEquity) {
        WalletV5 wallet = mock(WalletV5.class);
        when(wallet.totalEquity()).thenReturn(totalEquity);
        @SuppressWarnings("unchecked")
        BybitV5RestClient.ListResult<WalletV5> result = mock(BybitV5RestClient.ListResult.class);
        when(result.list()).thenReturn(List.of(wallet));
        @SuppressWarnings("unchecked")
        BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp = mock(BybitResponse.class);
        when(resp.isOk()).thenReturn(true);
        when(resp.result()).thenReturn(result);
        when(bybit.getWalletBalance(anyString(), anyString(), anyString())).thenReturn(resp);
    }

    @Test
    void computesPercentAgainstEquity() {
        // -$10 PnL on $1000 equity = -1.0%
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(new BigDecimal("-10.00"));
        stubEquity("1000.00");

        BigDecimal pct = calc.todayPnlPercent(account);
        assertNotNull(pct);
        assertEquals(0, new BigDecimal("-1.000000").compareTo(pct));
    }

    @Test
    void positiveRealizedPnlProducesPositivePercent() {
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(new BigDecimal("25.00"));
        stubEquity("500.00");

        BigDecimal pct = calc.todayPnlPercent(account);
        assertEquals(0, new BigDecimal("5.000000").compareTo(pct));
    }

    @Test
    void zeroPnlProducesZeroPercent() {
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(BigDecimal.ZERO);
        stubEquity("1000");

        BigDecimal pct = calc.todayPnlPercent(account);
        assertEquals(0, BigDecimal.ZERO.compareTo(pct));
    }

    @Test
    void equityFetchFailureFailsOpen() {
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(new BigDecimal("-100"));
        // Bybit returns isOk()=false
        @SuppressWarnings("unchecked")
        BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp = mock(BybitResponse.class);
        when(resp.isOk()).thenReturn(false);
        when(bybit.getWalletBalance(anyString(), anyString(), anyString())).thenReturn(resp);

        // Null lets GuardrailPolicy skip the daily-halt check rather than block
        // legitimate dispatches on a stale wallet endpoint.
        assertNull(calc.todayPnlPercent(account));
    }

    @Test
    void equityFetchExceptionFailsOpen() {
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(new BigDecimal("-100"));
        when(bybit.getWalletBalance(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        assertNull(calc.todayPnlPercent(account));
    }

    @Test
    void equityCacheAvoidsRepeatBybitCalls() {
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(new BigDecimal("-5"));
        stubEquity("1000");

        calc.todayPnlPercent(account);
        calc.todayPnlPercent(account);
        calc.todayPnlPercent(account);
        // 60s TTL — only one call to Bybit despite three lookups.
        verify(bybit, times(1)).getWalletBalance(anyString(), anyString(), anyString());
    }

    @Test
    void zeroEquityReturnsNullToAvoidDivideByZero() {
        when(tradeRepo.sumRealizedPnlSince(eq(1L), any(Instant.class)))
                .thenReturn(new BigDecimal("-10"));
        stubEquity("0");
        assertNull(calc.todayPnlPercent(account));
    }

    @Test
    void utcMidnightIsTodayAtZeroUtc() {
        Instant midnight = DailyPnlCalculator.utcMidnight();
        ZonedDateTime z = midnight.atZone(ZoneOffset.UTC);
        assertEquals(0, z.getHour());
        assertEquals(0, z.getMinute());
        assertEquals(0, z.getSecond());
        assertEquals(0, z.getNano());
    }
}
