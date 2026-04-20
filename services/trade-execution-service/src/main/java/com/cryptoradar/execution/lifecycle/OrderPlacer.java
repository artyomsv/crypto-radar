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
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
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
    private final ExecutionEventRepository eventRepo;

    public OrderPlacer(BybitV5RestClient bybit, InstrumentRegistry instruments,
                       ExecutedTradeRepository tradeRepo, ExecutionEventRepository eventRepo) {
        this.bybit = bybit;
        this.instruments = instruments;
        this.tradeRepo = tradeRepo;
        this.eventRepo = eventRepo;
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

        // 2. Compute qty from live equity + account risk %.
        double equity = fetchEquity(account);
        double riskPct = account.getRiskPercent().doubleValue();
        double qtyStep = instruments.qtyStepFor(account.getEnvironment(), req.symbol());
        double qty = RUnitMath.computeQty(equity, riskPct,
                req.entryPrice().doubleValue(), req.stopPrice().doubleValue(), qtyStep);
        if (qty <= 0) {
            return fail(account, req, "qty computed as zero — skip");
        }

        // 3. Insert row with PENDING_PLACE
        ExecutedTrade trade = new ExecutedTrade();
        trade.setExchangeAccountId(account.getId());
        trade.setSignalId(req.signalId());
        trade.setSymbol(req.symbol());
        trade.setDirection(req.direction());
        trade.setStrategy(req.strategy());
        trade.setStatus(TradeStatus.PENDING_PLACE);
        trade.setStopPrice(req.stopPrice());
        trade.setTargetPrice(req.targetPrice());
        trade.setDynamicStopPrice(req.stopPrice());
        trade.setLeverage(account.getDefaultLeverage());
        trade.setQty(BigDecimal.valueOf(qty));
        tradeRepo.persist(trade);
        trade.setExchangeOrderLinkId("ex-" + trade.getId());

        // 4. Place order
        String side = "LONG".equals(req.direction()) ? "Buy" : "Sell";
        PlaceOrderRequest orderReq = new PlaceOrderRequest(
                "linear", req.symbol(), side, "Market",
                String.valueOf(qty),
                req.targetPrice().toPlainString(),
                req.stopPrice().toPlainString(),
                "Full", "Market", "Market",
                trade.getExchangeOrderLinkId(), null);

        BybitResponse<PlaceOrderResult> resp;
        try {
            resp = bybit.placeOrder(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted(), orderReq);
        } catch (RuntimeException e) {
            LOG.errorf(e, "placeOrder threw for %s/%s", req.symbol(), req.direction());
            return fail(account, req, "Bybit call exception: " + e.getMessage());
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
        logEvent(account, trade, ExecutionEventType.ORDER_REJECTED,
                Map.of("retCode", resp.retCode(), "retMsg", resp.retMsg()));
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
        eventRepo.persist(ev);
    }
}
