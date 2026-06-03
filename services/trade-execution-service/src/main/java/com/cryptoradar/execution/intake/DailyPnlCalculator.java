package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.WalletV5;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes per-account today-realized-PnL percent for the daily-loss gate.
 *
 * <p>Numerator: SUM of {@code realized_pnl_usdt} on trades closed since
 * the most recent UTC midnight. Denominator: total Bybit equity, cached
 * per-account for {@code execution.daily-pnl.equity-cache-ttl-sec}
 * (default 60s) to avoid spamming the wallet endpoint on every dispatch.
 *
 * <p>Open-position unrealized PnL is intentionally excluded from the
 * numerator — counting it would create oscillation as quote prices move,
 * potentially blocking entries on transient drawdowns. The gate fires on
 * realized losses only, which is the conservative choice.
 *
 * <p>Fail-open semantics: if the Bybit equity fetch fails, returns null
 * percent so the guardrail policy falls through (BigDecimal null check
 * already in {@link com.cryptoradar.execution.policy.GuardrailPolicy}).
 */
@ApplicationScoped
public class DailyPnlCalculator {

    private static final Logger LOG = Logger.getLogger(DailyPnlCalculator.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Inject ExecutedTradeRepository tradeRepo;
    @Inject BybitV5RestClient bybit;
    @Inject ExecutionSettingsService executionSettings;

    private final ConcurrentHashMap<Long, EquityCacheEntry> equityCache = new ConcurrentHashMap<>();

    public BigDecimal todayPnlPercent(ExchangeAccount account) {
        Instant midnight = utcMidnight();
        BigDecimal realized = tradeRepo.sumRealizedPnlSince(account.getId(), midnight);
        BigDecimal equity = cachedEquity(account);
        if (equity == null || equity.signum() <= 0) return null;
        return realized.divide(equity, 6, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    private BigDecimal cachedEquity(ExchangeAccount account) {
        long ttl = executionSettings.snapshot().dailyPnlEquityCacheTtlSec();
        EquityCacheEntry cached = equityCache.get(account.getId());
        if (cached != null && cached.isFresh(ttl)) return cached.equity();
        BigDecimal fresh = fetchEquity(account);
        if (fresh != null) {
            equityCache.put(account.getId(), new EquityCacheEntry(fresh, Instant.now()));
        }
        return fresh;
    }

    private BigDecimal fetchEquity(ExchangeAccount account) {
        try {
            BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp =
                    bybit.getWalletBalance(account.getEnvironment(),
                            account.getApiKeyEncrypted(), account.getApiSecretEncrypted());
            if (!resp.isOk() || resp.result() == null || resp.result().list().isEmpty()) {
                return null;
            }
            String totalEquity = resp.result().list().get(0).totalEquity();
            if (totalEquity == null || totalEquity.isEmpty()) return null;
            return new BigDecimal(totalEquity);
        } catch (RuntimeException e) {
            LOG.warnf(e, "DailyPnlCalculator equity fetch failed for account %d", account.getId());
            return null;
        }
    }

    static Instant utcMidnight() {
        return ZonedDateTime.now(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private record EquityCacheEntry(BigDecimal equity, Instant cachedAt) {
        boolean isFresh(long ttlSec) {
            return Instant.now().getEpochSecond() - cachedAt.getEpochSecond() < ttlSec;
        }
    }
}
