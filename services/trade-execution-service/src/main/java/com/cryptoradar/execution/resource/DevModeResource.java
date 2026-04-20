package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.lifecycle.OrderPlacer;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.resource.dto.InjectSignalRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * Dev-only endpoints for smoke testing. Behind a feature flag — in prod the
 * flag is false and POSTs return 403.
 */
@Path("/api/execution/test")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevModeResource {

    private final boolean devEnabled;
    private final ExchangeAccountRepository accountRepo;
    private final OrderPlacer orderPlacer;

    public DevModeResource(@ConfigProperty(name = "execution.dev-mode.enabled") boolean devEnabled,
                           ExchangeAccountRepository accountRepo, OrderPlacer orderPlacer) {
        this.devEnabled = devEnabled;
        this.accountRepo = accountRepo;
        this.orderPlacer = orderPlacer;
    }

    @POST
    @Path("/inject-signal")
    public Response inject(InjectSignalRequest req) {
        if (!devEnabled) {
            return Response.status(403).entity(Map.of("error", "dev-mode disabled")).build();
        }
        for (ExchangeAccount a : accountRepo.listAll()) {
            orderPlacer.place(a, new OrderPlacer.PlacementRequest(
                    req.symbol(), req.direction(), req.strategy(),
                    "inject-" + System.currentTimeMillis(),
                    req.entryPrice(), req.stopPrice(), req.targetPrice()));
        }
        return Response.accepted().entity(Map.of("status", "dispatched")).build();
    }
}
