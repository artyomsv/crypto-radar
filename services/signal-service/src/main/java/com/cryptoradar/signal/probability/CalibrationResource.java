package com.cryptoradar.signal.probability;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the shadow probability gate's calibration report — the reliability
 * curve used to judge whether the stats and LLM probabilities are trustworthy
 * before any promotion to a live gate.
 */
@Path("/api/signals/probability")
public class CalibrationResource {

    @Inject
    CalibrationReporter reporter;

    @GET
    @Path("/calibration")
    @Produces(MediaType.APPLICATION_JSON)
    public CalibrationReporter.Report calibration() {
        return reporter.report();
    }
}
