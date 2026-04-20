package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.WalletV5;
import com.cryptoradar.execution.lifecycle.OrderPlacer;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.cryptoradar.execution.resource.dto.CloseAllRequest;
import com.cryptoradar.execution.resource.dto.EventView;
import com.cryptoradar.execution.resource.dto.KillSwitchRequest;
import com.cryptoradar.execution.resource.dto.PositionView;
import com.cryptoradar.execution.resource.dto.TradeView;
import com.cryptoradar.execution.resource.dto.WalletSnapshot;
import com.cryptoradar.execution.resource.dto.WhyView;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only + mutating endpoints for the trading surface (wallet, positions,
 * trades, events, kill-switch, close-all, close-one). Shares the
 * {@code /api/execution/accounts} base with {@link AccountResource} — all
 * trading paths include {@code {accountId}} in the method-level @Path so the
 * JAX-RS router can disambiguate from {@code AccountResource}'s CRUD on
 * {@code /{id}}.
 */
@Path("/api/execution/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TradingResource {

    private static final Logger LOG = Logger.getLogger(TradingResource.class);
    private static final String CONFIRM_CLOSE_ALL = "CLOSE_ALL";

    private final ExchangeAccountRepository accountRepo;
    private final ExecutedTradeRepository tradeRepo;
    private final ExecutionEventRepository eventRepo;
    private final BybitV5RestClient bybit;
    private final OrderPlacer orderPlacer;

    public TradingResource(ExchangeAccountRepository accountRepo, ExecutedTradeRepository tradeRepo,
                           ExecutionEventRepository eventRepo, BybitV5RestClient bybit,
                           OrderPlacer orderPlacer) {
        this.accountRepo = accountRepo;
        this.tradeRepo = tradeRepo;
        this.eventRepo = eventRepo;
        this.bybit = bybit;
        this.orderPlacer = orderPlacer;
    }

    @GET
    @Path("/{accountId}/wallet")
    public Response wallet(@PathParam("accountId") Long id) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        try {
            BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp =
                    bybit.getWalletBalance(a.getEnvironment(), a.getApiKeyEncrypted(), a.getApiSecretEncrypted());
            if (!resp.isOk() || resp.result() == null || resp.result().list().isEmpty()) {
                return Response.status(502).entity(Map.of("error", "Bybit wallet fetch failed")).build();
            }
            WalletV5 w = resp.result().list().get(0);
            int openCount = tradeRepo.countOpenForAccount(id);
            return Response.ok(new WalletSnapshot(
                    bd(w.totalEquity()), bd(w.totalAvailableBalance()),
                    bd(w.totalPerpUPL()), BigDecimal.ZERO,   // today-realized left for future iteration
                    openCount)).build();
        } catch (RuntimeException e) {
            LOG.warnf(e, "wallet fetch failed for account %d", id);
            return Response.status(502)
                    .entity(Map.of("error", "Bybit wallet fetch failed (see server logs)"))
                    .build();
        }
    }

    @GET
    @Path("/{accountId}/positions")
    public List<PositionView> positions(@PathParam("accountId") Long id) {
        return tradeRepo.findOpenForAccount(id).stream().map(PositionView::of).toList();
    }

    @GET
    @Path("/{accountId}/trades")
    public List<TradeView> trades(@PathParam("accountId") Long id,
                                   @QueryParam("limit") @DefaultValue("50") int limit) {
        return tradeRepo.findClosedSince(id, Instant.EPOCH, limit).stream().map(TradeView::of).toList();
    }

    @GET
    @Path("/{accountId}/events")
    public List<EventView> events(@PathParam("accountId") Long id,
                                   @QueryParam("limit") @DefaultValue("100") int limit) {
        return eventRepo.findRecentForAccount(id, limit).stream().map(EventView::of).toList();
    }

    @GET
    @Path("/{accountId}/trades/{tradeId}/why")
    public Response why(@PathParam("accountId") Long accountId, @PathParam("tradeId") Long tradeId) {
        ExecutedTrade t = tradeRepo.findById(tradeId);
        if (t == null || !t.getExchangeAccountId().equals(accountId)) return Response.status(404).build();
        // Join with signal_outcomes via signalId. For now, return the metadata we have locally.
        // Later: native query against signal_outcomes for dimension scores + AI analysis.
        return Response.ok(new WhyView(
                t.getId(), t.getSignalId(), t.getSymbol(), t.getDirection(), t.getStrategy(),
                t.getOpenedAt(), Map.of(
                        "note", "Join with signal_outcomes for dimension scores — phase 1 stub",
                        "signalId", t.getSignalId()
                ))).build();
    }

    @POST
    @Path("/{accountId}/kill-switch")
    @Transactional
    public Response killSwitch(@PathParam("accountId") Long id, KillSwitchRequest req) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        a.setKillSwitch(req.enabled());
        return Response.ok(Map.of("killSwitch", a.isKillSwitch())).build();
    }

    @POST
    @Path("/{accountId}/close-all")
    @Transactional
    public Response closeAll(@PathParam("accountId") Long id, CloseAllRequest req) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        if (!CONFIRM_CLOSE_ALL.equals(req.confirm())) {
            return Response.status(400)
                    .entity(Map.of("error", "confirm field must be '" + CONFIRM_CLOSE_ALL + "'"))
                    .build();
        }
        List<ExecutedTrade> open = tradeRepo.findOpenForAccount(id);
        for (ExecutedTrade t : open) {
            orderPlacer.close(a, t, ExitReason.KILL);
        }
        return Response.ok(Map.of("closedCount", open.size())).build();
    }

    @POST
    @Path("/{accountId}/trades/{tradeId}/close")
    @Transactional
    public Response closeOne(@PathParam("accountId") Long accountId, @PathParam("tradeId") Long tradeId) {
        ExchangeAccount a = accountRepo.findById(accountId);
        if (a == null) return Response.status(404).build();
        ExecutedTrade t = tradeRepo.findById(tradeId);
        if (t == null || !t.getExchangeAccountId().equals(accountId)) return Response.status(404).build();
        orderPlacer.close(a, t, ExitReason.MANUAL);
        return Response.ok(TradeView.of(t)).build();
    }

    private static BigDecimal bd(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
