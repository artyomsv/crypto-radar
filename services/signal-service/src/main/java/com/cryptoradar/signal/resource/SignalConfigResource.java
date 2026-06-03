package com.cryptoradar.signal.resource;

import com.cryptoradar.signal.config.ConfigService;
import com.cryptoradar.signal.config.ConfigVersionRepository;
import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.config.SignalConfigVersion;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for signal engine config versioning.
 *
 * <p>All write paths go through {@link ConfigService} for validation.
 * Read paths that don't touch the DB use the service's cached
 * {@link java.util.concurrent.atomic.AtomicReference} directly.
 */
@Path("/api/signals/config")
@Produces(MediaType.APPLICATION_JSON)
public class SignalConfigResource {

    private static final int MAX_LIST_LIMIT = 200;
    private static final int DEFAULT_LIST_LIMIT = 50;

    private final ConfigService configService;
    private final ConfigVersionRepository repository;

    public SignalConfigResource(ConfigService configService,
                                ConfigVersionRepository repository) {
        this.configService = configService;
        this.repository = repository;
    }

    /**
     * Returns the active config version with full metadata.
     * 404 if no active version exists (should not happen after DB seed).
     */
    @GET
    @Transactional
    public Response getActiveConfig() {
        return repository.findActive()
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "No active config version found"))
                        .build());
    }

    /**
     * Lists versions, newest first.
     *
     * @param limit  max rows to return (clamped to 200)
     * @param offset row offset for pagination
     */
    @GET
    @Path("/versions")
    @Transactional
    public List<SignalConfigVersion> listVersions(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        int clampedLimit = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        int safeOffset = Math.max(offset, 0);
        return repository.listVersions(clampedLimit, safeOffset);
    }

    /**
     * Returns a single version by its surrogate ID.
     * 404 if not found.
     */
    @GET
    @Path("/versions/{id}")
    @Transactional
    public Response getVersion(@PathParam("id") long id) {
        return repository.findById(id)
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "Config version not found: " + id))
                        .build());
    }

    /**
     * Creates a new immutable config version. NOT activated.
     * Returns 400 with {@code {"error":"..."}} on validation failure.
     * Returns 201 with the created version on success.
     */
    @POST
    @Path("/versions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createVersion(CreateVersionRequest request) {
        if (request == null || request.config() == null) {
            return Response.status(400)
                    .entity(Map.of("error", "Request body with 'config' field is required"))
                    .build();
        }
        try {
            SignalConfigVersion created = configService.saveVersion(
                    request.config(),
                    request.description(),
                    request.parentVersionId());
            return Response.status(201).entity(created).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Atomically activates the given version. All other versions become inactive.
     * Engine reloads on the next 30 s scheduler tick.
     */
    @POST
    @Path("/versions/{id}/activate")
    public Response activateVersion(@PathParam("id") long id) {
        try {
            SignalConfigVersion activated = configService.activateVersion(id);
            return Response.ok(activated).build();
        } catch (NotFoundException e) {
            return Response.status(404)
                    .entity(Map.of("error", "Config version not found: " + id))
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Request DTO
    // -------------------------------------------------------------------------

    public record CreateVersionRequest(
            SignalConfig config,
            String description,
            Long parentVersionId
    ) {}
}
