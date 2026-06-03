package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.intake.ExecutionSettingsService;
import com.cryptoradar.execution.intake.ExecutionSettingsService.TelegramRuntime;
import com.cryptoradar.execution.notify.TelegramNotifier;
import com.cryptoradar.execution.notify.TelegramNotifier.SendResult;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Runtime-tunable execution gates. PUT replaces the full row (single-row table
 * enforced by DB CHECK); GET returns the cached snapshot. Reads do not touch
 * the DB; the service polls every 30s.
 */
@Path("/api/execution/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExecutionSettingsResource {

    private final ExecutionSettingsService service;
    private final TelegramNotifier telegram;

    public ExecutionSettingsResource(ExecutionSettingsService service, TelegramNotifier telegram) {
        this.service = service;
        this.telegram = telegram;
    }

    @GET
    public ExecutionSettingsService.Snapshot get() {
        return service.snapshot();
    }

    @PUT
    public Response update(ExecutionSettingsService.Snapshot incoming) {
        if (incoming == null) {
            return Response.status(400)
                    .entity(Map.of("error", "Request body required"))
                    .build();
        }
        try {
            return Response.ok(service.update(incoming)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Sends a one-off test message to verify the Telegram connection. The body
     * may carry an unsaved {@code botToken}/{@code chatId} so the user can test
     * before committing; blank fields fall back to the saved credentials. The
     * token is never echoed back.
     */
    @POST
    @Path("/telegram/test")
    public Response testTelegram(TelegramTestRequest request) {
        TelegramRuntime saved = service.telegramRuntime();
        String token = firstNonBlank(request == null ? null : request.botToken(), saved.botToken());
        String chatId = firstNonBlank(request == null ? null : request.chatId(), saved.chatId());

        SendResult result = telegram.send(token, chatId,
                "✅ <b>CryptoRadar</b> test notification — Telegram is wired up.");
        if (result.ok()) {
            return Response.ok(Map.of("ok", true)).build();
        }
        return Response.ok(Map.of("ok", false, "error", result.error())).build();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b;
    }

    public record TelegramTestRequest(String botToken, String chatId) {}
}
