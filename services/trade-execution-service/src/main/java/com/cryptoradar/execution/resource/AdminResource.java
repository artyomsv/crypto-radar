package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.lifecycle.OrderReconciler;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * One-shot administrative endpoints for repairing data integrity issues that
 * predate a code fix. Hosted under the same {@code /api/execution/accounts}
 * base so the gateway proxy can route through unchanged.
 */
@Path("/api/execution/accounts")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    private static final int MAX_BACKFILL_LIMIT = 200;

    private final OrderReconciler reconciler;
    private final ExchangeAccountRepository accountRepo;

    public AdminResource(OrderReconciler reconciler, ExchangeAccountRepository accountRepo) {
        this.reconciler = reconciler;
        this.accountRepo = accountRepo;
    }

    @POST
    @Path("/{accountId}/admin/backfill-closes")
    public Response backfillCloses(@PathParam("accountId") Long accountId,
                                    @QueryParam("limit") @DefaultValue("50") int limit) {
        if (accountRepo.findById(accountId) == null) return Response.status(404).build();
        int safeLimit = Math.min(Math.max(1, limit), MAX_BACKFILL_LIMIT);
        int recovered = reconciler.backfillIncompleteCloses(accountId, safeLimit);
        return Response.ok(Map.of(
                "accountId", accountId,
                "scanned", safeLimit,
                "recovered", recovered)).build();
    }

    @POST
    @Path("/{accountId}/admin/repair-exit-reasons")
    public Response repairExitReasons(@PathParam("accountId") Long accountId,
                                       @QueryParam("limit") @DefaultValue("100") int limit) {
        if (accountRepo.findById(accountId) == null) return Response.status(404).build();
        int safeLimit = Math.min(Math.max(1, limit), MAX_BACKFILL_LIMIT);
        int reclassified = reconciler.repairMislabeledExitReasons(accountId, safeLimit);
        return Response.ok(Map.of(
                "accountId", accountId,
                "scanned", safeLimit,
                "reclassified", reclassified)).build();
    }
}
