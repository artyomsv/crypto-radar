package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.core.RUnitMath;
import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.PlaceOrderRequest;
import com.cryptoradar.execution.client.bybit.dto.PlaceOrderResult;
import com.cryptoradar.execution.client.bybit.dto.WalletV5;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.intake.StrategyPerformanceSizer;
import com.cryptoradar.execution.notify.ExecutionEventService;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Places market orders on Bybit with TP/SL attached. Computes quantity via
 * {@link RUnitMath} from the account's risk % and the signal's entry/stop distance.
 */
@ApplicationScoped
public class OrderPlacer {

    private static final Logger LOG = Logger.getLogger(OrderPlacer.class);
    private static final int RETCODE_OK = 0;
    private static final int RETCODE_LEVERAGE_UNCHANGED = 110043;
    private static final int RETCODE_DUPLICATE_ORDER = 110061;
    private static final int RETCODE_INSUFFICIENT_MARGIN = 110007;
    private static final double FALLBACK_EQUITY = 1000.0;

    private final BybitV5RestClient bybit;
    private final InstrumentRegistry instruments;
    private final ExecutedTradeRepository tradeRepo;
    private final ExecutionEventService events;
    private final StrategyPerformanceSizer sizer;
    private final StrategyExitPolicy exitPolicy;

    public OrderPlacer(BybitV5RestClient bybit, InstrumentRegistry instruments,
                       ExecutedTradeRepository tradeRepo, ExecutionEventService events,
                       StrategyPerformanceSizer sizer, StrategyExitPolicy exitPolicy) {
        this.bybit = bybit;
        this.instruments = instruments;
        this.tradeRepo = tradeRepo;
        this.events = events;
        this.sizer = sizer;
        this.exitPolicy = exitPolicy;
    }

    public record PlacementRequest(String symbol, String direction, String strategy,
                                    String signalId, BigDecimal entryPrice,
                                    BigDecimal stopPrice, BigDecimal targetPrice) {}

    @Transactional
    public ExecutedTrade place(ExchangeAccount account, PlacementRequest req) {
        // 1. Set leverage (idempotent)
        BybitResponse<Map<String, Object>> levResp = bybit.setLeverage(
                account.getEnvironment(), account.getApiKeyEncrypted(),
                account.getApiSecretEncrypted(), req.symbol(), account.getDefaultLeverage());
        if (!levResp.isOk() && levResp.retCode() != RETCODE_LEVERAGE_UNCHANGED) {
            LOG.warnf("setLeverage failed for %s: retCode=%d retMsg=%s",
                    req.symbol(), levResp.retCode(), levResp.retMsg());
        }

        // 2. Compute qty from live equity + account risk %, scaled by the
        //    per-cell sizing multiplier. Strong empirical winners size up
        //    (1.25-1.5x), weak losers size down (0.5x). See
        //    StrategyPerformanceSizer for the bucket thresholds.
        double equity = fetchEquity(account);
        double baseRiskPct = account.getRiskPercent().doubleValue();
        double sizeMultiplier = sizer.multiplierFor(req.symbol(), req.direction(), req.strategy());
        double effectiveRiskPct = baseRiskPct * sizeMultiplier;
        if (Math.abs(sizeMultiplier - 1.0) > 1e-9) {
            StrategyPerformanceSizer.Cached d = sizer.lastDecisionFor(req.symbol(), req.direction(), req.strategy());
            LOG.infof("SIZER %s %s/%s multiplier=%.2f (sample=%d totalR=%.2f) — risk %.3f%% -> %.3f%%",
                    req.symbol(), req.direction(), req.strategy(), sizeMultiplier,
                    d != null ? d.sampleSize() : 0, d != null ? d.totalR() : 0.0,
                    baseRiskPct, effectiveRiskPct);
        }
        double qtyStep = instruments.qtyStepFor(account.getEnvironment(), req.symbol());
        double qty = RUnitMath.computeQty(equity, effectiveRiskPct,
                req.entryPrice().doubleValue(), req.stopPrice().doubleValue(), qtyStep);
        if (qty <= 0) {
            return fail(account, req, "qty computed as zero — skip");
        }

        // Long-horizon breakout strategies (turtle/donchian) carry no fixed
        // profit target — they exit on a reverse-Donchian breach (see
        // DonchianExitMonitor) with the 2N stop as the catastrophic backstop.
        // signal-service still ships a far-away placeholder target that trips
        // Bybit's "TakeProfit < 10% of base price" rule (retCode 10001) and
        // rejects the whole entry. Drop it here so the order carries only the
        // stop-loss, and never persist the misleading value on the row.
        BigDecimal effectiveTarget =
                exitPolicy.isLongHorizon(req.strategy()) ? null : req.targetPrice();

        // 3. Insert row with PENDING_PLACE
        ExecutedTrade trade = new ExecutedTrade();
        trade.setExchangeAccountId(account.getId());
        trade.setSignalId(req.signalId());
        trade.setSymbol(req.symbol());
        trade.setDirection(req.direction());
        trade.setStrategy(req.strategy());
        trade.setStatus(TradeStatus.PENDING_PLACE);
        // Persist the intended entry up front. WS execution event refines this
        // to the real fill price; without it, R-multiple math breaks if the
        // execution event is missed (Bybit private WS keepalive bug).
        trade.setEntryPrice(req.entryPrice());
        trade.setStopPrice(req.stopPrice());
        trade.setTargetPrice(effectiveTarget);
        trade.setDynamicStopPrice(req.stopPrice());
        trade.setLeverage(account.getDefaultLeverage());
        trade.setQty(BigDecimal.valueOf(qty));
        tradeRepo.persist(trade);
        trade.setExchangeOrderLinkId("ex-" + trade.getId());

        // 4. Place order. takeProfit is omitted (NON_NULL serialization) when
        //    there is no target — long-horizon strategies, or a caller that
        //    supplied none.
        String side = "LONG".equals(req.direction()) ? "Buy" : "Sell";
        String takeProfit = effectiveTarget == null ? null : effectiveTarget.toPlainString();
        PlaceOrderRequest orderReq = new PlaceOrderRequest(
                "linear", req.symbol(), side, "Market",
                String.valueOf(qty),
                takeProfit,
                req.stopPrice().toPlainString(),
                "Full", "Market", "Market",
                trade.getExchangeOrderLinkId(), null);

        BybitResponse<PlaceOrderResult> resp;
        try {
            resp = bybit.placeOrder(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted(), orderReq);
        } catch (RuntimeException e) {
            // Mutate the already-persisted PENDING_PLACE row to FAILED instead of
            // creating a second row. Without this, every connectivity blip left an
            // orphan PENDING_PLACE row that polluted the reconciler's queue and
            // dedup gate. Hibernate dirty-checks the change on tx commit.
            LOG.error("placeOrder threw for " + req.symbol() + "/" + req.direction(), e);
            trade.setStatus(TradeStatus.FAILED);
            logEvent(account, trade, ExecutionEventType.ORDER_REJECTED,
                    Map.of("reason", "Bybit call exception: " + e.getMessage()));
            return trade;
        }

        if (resp.retCode() == RETCODE_OK || resp.retCode() == RETCODE_DUPLICATE_ORDER) {
            // Duplicate-order retcode can come back with an empty result payload; guard against null orderId.
            String orderId = (resp.result() == null) ? null : resp.result().orderId();
            if (orderId != null) {
                trade.setExchangeOrderId(orderId);
            }
            trade.setStatus(TradeStatus.OPEN);   // WS will refine to OPEN-with-fill later
            logEvent(account, trade, ExecutionEventType.ORDER_PLACED,
                    Map.of("orderId", orderId == null ? "" : orderId, "qty", qty));
            return trade;
        }
        if (resp.retCode() == RETCODE_INSUFFICIENT_MARGIN) {
            trade.setStatus(TradeStatus.FAILED);
            logEvent(account, trade, ExecutionEventType.SIGNAL_BLOCKED_INSUFFICIENT_MARGIN,
                    Map.of("retMsg", resp.retMsg()));
            return trade;
        }
        trade.setStatus(TradeStatus.FAILED);
        // Capture the request payload too — "Qty invalid" with retCode 10001
        // is a generic param-error reply and the message alone doesn't say
        // which field Bybit rejected. With qty / entry / stop / target in the
        // event we can diff a failing order against a known-good one to spot
        // the offending value next time.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("retCode", resp.retCode());
        meta.put("retMsg", resp.retMsg());
        meta.put("qty", qty);
        meta.put("entryPrice", req.entryPrice().toPlainString());
        meta.put("stopPrice", req.stopPrice().toPlainString());
        meta.put("targetPrice", effectiveTarget == null ? "none" : effectiveTarget.toPlainString());
        meta.put("leverage", account.getDefaultLeverage());
        logEvent(account, trade, ExecutionEventType.ORDER_REJECTED, meta);
        return trade;
    }

    @Transactional
    public void close(ExchangeAccount account, ExecutedTrade trade, ExitReason reason) {
        String side = "LONG".equals(trade.getDirection()) ? "Sell" : "Buy";
        PlaceOrderRequest closeReq = new PlaceOrderRequest(
                "linear", trade.getSymbol(), side, "Market",
                trade.getQty().toPlainString(),
                null, null, null, null, null,
                trade.getExchangeOrderLinkId() + "-close", true);
        try {
            bybit.placeOrder(account.getEnvironment(), account.getApiKeyEncrypted(),
                    account.getApiSecretEncrypted(), closeReq);
            trade.setStatus(TradeStatus.CLOSING);
            trade.setExitReason(reason);
        } catch (RuntimeException e) {
            LOG.errorf(e, "close order failed for trade %d", trade.getId());
        }
    }

    /**
     * Fetch current wallet equity. In Plan 2b this calls Bybit; in a later
     * iteration we can cache this in memory and refresh via WS.
     */
    private double fetchEquity(ExchangeAccount account) {
        try {
            BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp =
                    bybit.getWalletBalance(account.getEnvironment(),
                            account.getApiKeyEncrypted(), account.getApiSecretEncrypted());
            if (resp.isOk() && !resp.result().list().isEmpty()) {
                return Double.parseDouble(resp.result().list().get(0).totalEquity());
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "wallet fetch failed — falling back to %s equity", FALLBACK_EQUITY);
        }
        return FALLBACK_EQUITY;
    }

    private ExecutedTrade fail(ExchangeAccount account, PlacementRequest req, String reason) {
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSignalId(req.signalId());
        t.setSymbol(req.symbol());
        t.setDirection(req.direction());
        t.setStrategy(req.strategy());
        t.setStatus(TradeStatus.FAILED);
        tradeRepo.persist(t);
        logEvent(account, t, ExecutionEventType.ORDER_REJECTED, Map.of("reason", reason));
        return t;
    }

    private void logEvent(ExchangeAccount account, ExecutedTrade trade,
                           ExecutionEventType type, Map<String, Object> metadata) {
        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(type);
        ev.setSignalId(trade.getSignalId());
        ev.setExecutedTradeId(trade.getId());
        ev.setMetadata(metadata);
        events.record(ev);
    }
}
