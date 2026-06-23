package com.cryptoradar.signal.probability;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the shadow probability gate's calibration report. Without a tag it
 * returns the v2-1to1-flip control's curve (unchanged contract); {@code ?tag=}
 * selects another config, e.g. v3-feature-dir.
 */
@Path("/api/signals/probability")
public class CalibrationResource {

    private static final String DEFAULT_TAG = "v2-1to1-flip";

    @Inject
    CalibrationReporter reporter;

    @GET
    @Path("/calibration")
    @Produces(MediaType.APPLICATION_JSON)
    public CalibrationReporter.Report calibration(@QueryParam("tag") String tag) {
        return reporter.report(tag == null || tag.isBlank() ? DEFAULT_TAG : tag);
    }
}
