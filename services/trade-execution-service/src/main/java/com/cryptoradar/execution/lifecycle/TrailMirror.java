package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.core.TrailCalculator;
import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.execution.client.MarketDataClient;
import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.TradingStopRequest;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled worker that advances trail stops on Bybit for every OPEN trade.
 * Uses shared TrailCalculator so the rung math matches signal-service's
 * OutcomeEvaluator exactly.
 */
@ApplicationScoped
public class TrailMirror {

    private static final Logger LOG = Logger.getLogger(TrailMirror.class);
    private static final int PRICE_SCALE = 8;
    private static final String CATEGORY_LINEAR = "linear";
    private static final String TPSL_MODE_FULL = "Full";
    private static final int POSITION_IDX_ONE_WAY = 0;

    private final ExecutedTradeRepository tradeRepo;
    private final ExchangeAccountRepository accountRepo;
    private final ExecutionEventRepository eventRepo;
    private final BybitV5RestClient bybit;
    private final MarketDataClient marketData;

    public TrailMirror(ExecutedTradeRepository tradeRepo, ExchangeAccountRepository accountRepo,
                       ExecutionEventRepository eventRepo, BybitV5RestClient bybit,
                       MarketDataClient marketData) {
        this.tradeRepo = tradeRepo;
        this.accountRepo = accountRepo;
        this.eventRepo = eventRepo;
        this.bybit = bybit;
        this.marketData = marketData;
    }

    @Scheduled(every = "${execution.trail.interval:60s}", delay = 30, delayUnit = TimeUnit.SECONDS)
    @Transactional
    public void tick() {
        accountRepo.listAll().forEach(this::processAccount);
    }

    private void processAccount(ExchangeAccount account) {
        for (ExecutedTrade trade : tradeRepo.findOpenForAccount(account.getId())) {
            try {
                processTrade(account, trade);
            } catch (RuntimeException e) {
                LOG.errorf(e, "trail-mirror error for trade %d", trade.getId());
            }
        }
    }

    void processTrade(ExchangeAccount account, ExecutedTrade trade) {
        BigDecimal price = marketData.getLastPrice(trade.getSymbol());
        if (price == null) {
            return;
        }
        BigDecimal entry = trade.getEntryPrice();
        BigDecimal stop = trade.getStopPrice();
        if (entry == null || stop == null) {
            return;
        }

        boolean isLong = "LONG".equals(trade.getDirection());
        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.signum() <= 0) {
            return;
        }

        BigDecimal mfePct = isLong
                ? price.subtract(entry).divide(entry, PRICE_SCALE, RoundingMode.HALF_UP)
                : entry.subtract(price).divide(entry, PRICE_SCALE, RoundingMode.HALF_UP);
        if (mfePct.signum() <= 0) {
            return;
        }

        double riskPct = risk.divide(entry, PRICE_SCALE, RoundingMode.HALF_UP).doubleValue() * 100.0;
        double mfePctD = mfePct.doubleValue() * 100.0;
        double mfeR = riskPct == 0 ? 0 : mfePctD / riskPct;

        TrailConfig config = TrailConfig.DEFAULT;
        double currentTrailR = trade.getTrailHighestR() == null
                ? 0.0
                : trade.getTrailHighestR().doubleValue();
        Optional<Double> newR = TrailCalculator.computeNewTrailR(mfeR, config, currentTrailR);
        if (newR.isEmpty()) {
            return;
        }

        double newRungR = newR.get();
        BigDecimal newStopPrice = isLong
                ? entry.add(risk.multiply(BigDecimal.valueOf(newRungR)))
                : entry.subtract(risk.multiply(BigDecimal.valueOf(newRungR)));

        TradingStopRequest req = new TradingStopRequest(
                CATEGORY_LINEAR, trade.getSymbol(),
                newStopPrice.toPlainString(), TPSL_MODE_FULL, POSITION_IDX_ONE_WAY);
        BybitResponse<Map<String, Object>> resp = bybit.setTradingStop(
                account.getEnvironment(), account.getApiKeyEncrypted(),
                account.getApiSecretEncrypted(), req);
        if (!resp.isOk()) {
            LOG.warnf("setTradingStop failed for trade %d: retCode=%d retMsg=%s",
                    trade.getId(), resp.retCode(), resp.retMsg());
            return;
        }

        trade.setTrailHighestR(BigDecimal.valueOf(newRungR));
        trade.setDynamicStopPrice(newStopPrice);
        if (trade.getTrailTriggeredAt() == null) {
            trade.setTrailTriggeredAt(Instant.now());
        }

        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(ExecutionEventType.TRAIL_UPDATED);
        ev.setExecutedTradeId(trade.getId());
        ev.setSignalId(trade.getSignalId());
        ev.setMetadata(Map.of("newTrailR", newRungR, "newStop", newStopPrice.toPlainString()));
        eventRepo.persist(ev);
    }
}
