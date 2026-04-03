package com.cryptoradar.signal.resource;

import com.cryptoradar.signal.model.SignalOverview;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.service.SignalService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/signals")
@Produces(MediaType.APPLICATION_JSON)
public class SignalResource {

    private final SignalService signalService;

    public SignalResource(SignalService signalService) {
        this.signalService = signalService;
    }

    @GET
    @Path("/overview")
    public SignalOverview getOverview() {
        return signalService.getSignalOverview();
    }

    @GET
    @Path("/{symbol}")
    public Response getSignal(@PathParam("symbol") String symbol) {
        TradingSignal signal = signalService.getSignal(symbol.toUpperCase());
        if (signal == null) {
            return Response.status(404)
                    .entity(Map.of("error", "No signal data for " + symbol.toUpperCase()))
                    .build();
        }
        return Response.ok(signal).build();
    }
}
