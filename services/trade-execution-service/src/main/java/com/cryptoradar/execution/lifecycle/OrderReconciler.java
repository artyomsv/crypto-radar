package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ClosedPnlV5;
import com.cryptoradar.execution.client.bybit.dto.PositionV5;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Startup + periodic drift check between local DB and Bybit position state.
 * Detects orphans (Bybit has a position we don't know about) and closed-externally
 * cases (local OPEN but Bybit has none — fetch closed-pnl to populate realized
 * P&L, fees, and exit reason).
 */
@ApplicationScoped
public class OrderReconciler {

    private static final Logger LOG = Logger.getLogger(OrderReconciler.class);

    private final ExecutedTradeRepository tradeRepo;
    private final ExchangeAccountRepository accountRepo;
    private final ExecutionEventRepository eventRepo;
    private final BybitV5RestClient bybit;

    public OrderReconciler(ExecutedTradeRepository tradeRepo, ExchangeAccountRepository accountRepo,
                           ExecutionEventRepository eventRepo, BybitV5RestClient bybit) {
        this.tradeRepo = tradeRepo;
        this.accountRepo = accountRepo;
        this.eventRepo = eventRepo;
        this.bybit = bybit;
    }

    void onStartup(@Observes StartupEvent ev) {
        try {
            reconcile();
        } catch (RuntimeException e) {
            LOG.warnf(e, "startup reconcile failed");
        }
    }

    @Scheduled(every = "${execution.reconcile.interval:60s}", delay = 45, delayUnit = TimeUnit.SECONDS)
    @Transactional
    public void reconcile() {
        accountRepo.listAll().forEach(this::reconcileAccount);
    }

    @Transactional
    public void reconcileAccount(ExchangeAccount account) {
        BybitResponse<BybitV5RestClient.ListResult<PositionV5>> resp;
        try {
            resp = bybit.getPositionList(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted());
        } catch (RuntimeException e) {
            LOG.warnf(e, "positionList fetch failed for account %d", account.getId());
            return;
        }
        if (!resp.isOk() || resp.result() == null) return;

        List<PositionV5> remote = resp.result().list();
        List<ExecutedTrade> local = tradeRepo.findOpenForAccount(account.getId());

        Set<String> remoteOpen = new HashSet<>();
        for (PositionV5 pos : remote) {
            if (!hasOpenSize(pos)) continue;
            remoteOpen.add(pos.symbol() + "|" + pos.side());
        }

        // Find local rows that are no longer on Bybit — closed externally.
        for (ExecutedTrade trade : local) {
            String side = "LONG".equals(trade.getDirection()) ? "Buy" : "Sell";
            String key = trade.getSymbol() + "|" + side;
            if (!remoteOpen.contains(key)) {
                closeFromReconcile(account, trade);
            } else {
                trade.setLastSyncAt(Instant.now());
            }
        }

        // Find remote positions that aren't tracked locally — orphans.
        for (PositionV5 pos : remote) {
            if (!hasOpenSize(pos)) continue;
            String direction = "Buy".equals(pos.side()) ? "LONG" : "SHORT";
            boolean tracked = local.stream().anyMatch(t ->
                    t.getSymbol().equals(pos.symbol()) && t.getDirection().equals(direction));
            if (!tracked) {
                createOrphan(account, pos, direction);
            }
        }
    }

    private void closeFromReconcile(ExchangeAccount account, ExecutedTrade trade) {
        BybitResponse<BybitV5RestClient.ListResult<ClosedPnlV5>> pnlResp;
        try {
            pnlResp = bybit.getClosedPnl(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted(),
                    trade.getSymbol(), 10);
        } catch (RuntimeException e) {
            LOG.warnf(e, "closedPnl fetch failed for trade %d", trade.getId());
            return;
        }
        if (!pnlResp.isOk() || pnlResp.result() == null || pnlResp.result().list().isEmpty()) return;

        // Most recent matching close; Bybit returns in descending createdTime order.
        ClosedPnlV5 match = pnlResp.result().list().get(0);

        trade.setStatus(TradeStatus.CLOSED);
        trade.setClosedAt(Instant.now());
        trade.setRealizedPnlUsdt(safeBd(match.closedPnl()));
        trade.setFeesUsdt(safeBd(match.openFee()).add(safeBd(match.closeFee())));
        trade.setExitPrice(safeBd(match.orderPrice()));
        trade.setExitReason(trade.getExitReason() != null ? trade.getExitReason() : ExitReason.TARGET);

        if (trade.getEntryPrice() != null && trade.getStopPrice() != null) {
            BigDecimal riskDist = trade.getEntryPrice().subtract(trade.getStopPrice()).abs();
            if (riskDist.signum() > 0 && trade.getExitPrice() != null) {
                BigDecimal pnlDist = "LONG".equals(trade.getDirection())
                        ? trade.getExitPrice().subtract(trade.getEntryPrice())
                        : trade.getEntryPrice().subtract(trade.getExitPrice());
                trade.setRealizedRMultiple(pnlDist.divide(riskDist, 4, RoundingMode.HALF_UP));
            }
        }

        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(ExecutionEventType.RECONCILE_CLOSED_EXTERNALLY);
        ev.setSignalId(trade.getSignalId());
        ev.setExecutedTradeId(trade.getId());
        ev.setMetadata(Map.of("closedPnl", match.closedPnl(), "orderPrice", match.orderPrice()));
        eventRepo.persist(ev);
    }

    private void createOrphan(ExchangeAccount account, PositionV5 pos, String direction) {
        ExecutedTrade orphan = new ExecutedTrade();
        orphan.setExchangeAccountId(account.getId());
        orphan.setSymbol(pos.symbol());
        orphan.setDirection(direction);
        orphan.setStatus(TradeStatus.OPEN);
        orphan.setEntryPrice(safeBd(pos.avgPrice()));
        orphan.setQty(safeBd(pos.size()));
        orphan.setStopPrice(safeBd(pos.stopLoss()));
        orphan.setTargetPrice(safeBd(pos.takeProfit()));
        orphan.setDynamicStopPrice(safeBd(pos.stopLoss()));
        orphan.setLeverage(Integer.parseInt(pos.leverage() == null ? "1" : pos.leverage()));
        tradeRepo.persist(orphan);

        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(ExecutionEventType.RECONCILE_ORPHAN_DETECTED);
        ev.setExecutedTradeId(orphan.getId());
        ev.setMetadata(Map.of("symbol", pos.symbol(), "side", pos.side()));
        eventRepo.persist(ev);
    }

    private static boolean hasOpenSize(PositionV5 pos) {
        if (pos.size() == null || "0".equals(pos.size())) return false;
        try {
            return new BigDecimal(pos.size()).signum() != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BigDecimal safeBd(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
