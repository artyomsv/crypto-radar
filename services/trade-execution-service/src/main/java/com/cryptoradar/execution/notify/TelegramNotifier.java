package com.cryptoradar.execution.notify;

import com.cryptoradar.execution.intake.ExecutionSettingsService;
import com.cryptoradar.execution.intake.ExecutionSettingsService.TelegramRuntime;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sends notifications to a Telegram chat via the Bot API {@code sendMessage}
 * endpoint. Self-contained (own {@link HttpClient} + {@link ObjectMapper}) like
 * the other thin exchange clients in this service.
 *
 * <p>The hot path ({@link #maybeNotify}) is fully fail-open: a missing config,
 * a disabled toggle, an un-subscribed event type, or any network error must
 * never block the caller's transaction or throw. The actual HTTP call is
 * offloaded to a single daemon thread so the database transaction that recorded
 * the event commits without waiting on Telegram.
 *
 * <p>The bot token is read from the in-memory {@link TelegramRuntime} cache and
 * is never logged. Error logs carry the Telegram error description, never the
 * token or the chat content.
 */
@ApplicationScoped
public class TelegramNotifier {

    private static final Logger LOG = Logger.getLogger(TelegramNotifier.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_MESSAGE_CHARS = 3500; // Telegram hard limit is 4096

    private final ExecutionSettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private ExecutorService dispatcher;

    public TelegramNotifier(ExecutionSettingsService settings) {
        this.settings = settings;
    }

    @PostConstruct
    void init() {
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-notifier");
            t.setDaemon(true);
            return t;
        });
    }

    void onStop(@Observes ShutdownEvent event) {
        if (dispatcher != null) {
            dispatcher.shutdown();
            try {
                if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                    dispatcher.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dispatcher.shutdownNow();
            }
        }
    }

    /**
     * Notifies if Telegram is enabled, configured, and this event type is in the
     * subscribed set. Reads config on the caller's thread (cheap, no DB) then
     * offloads the HTTP send. Never throws.
     */
    public void maybeNotify(ExecutionEvent ev) {
        if (ev == null || ev.getEventType() == null) return;
        TelegramRuntime rt = settings.telegramRuntime();
        if (!rt.enabled() || !rt.isSendable()) return;
        if (!rt.events().contains(ev.getEventType().name())) return;

        String text = format(ev);
        String token = rt.botToken();
        String chatId = rt.chatId();
        dispatcher.submit(() -> {
            SendResult result = send(token, chatId, text);
            if (!result.ok()) {
                LOG.warnf("telegram_notify_failed eventType=%s error=%s", ev.getEventType(), result.error());
            }
        });
    }

    /**
     * Synchronous send used by the Test button and (indirectly) by
     * {@link #maybeNotify}. Returns a result rather than throwing so callers can
     * surface the Telegram error verbatim to the UI.
     */
    public SendResult send(String botToken, String chatId, String text) {
        if (botToken == null || botToken.isBlank()) {
            return SendResult.fail("bot token not set");
        }
        if (chatId == null || chatId.isBlank()) {
            return SendResult.fail("chat id not set");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "text", truncate(text),
                    "parse_mode", "HTML",
                    "disable_web_page_preview", true));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken + "/sendMessage"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            if (resp.statusCode() == 200 && json.path("ok").asBoolean(false)) {
                return SendResult.success();
            }
            String desc = json.path("description").asText("HTTP " + resp.statusCode());
            return SendResult.fail(desc);
        } catch (java.io.IOException e) {
            return SendResult.fail("network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.fail("interrupted");
        }
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_MESSAGE_CHARS ? text : text.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    private static String format(ExecutionEvent ev) {
        StringBuilder sb = new StringBuilder();
        sb.append(emojiFor(ev)).append(" <b>").append(escape(ev.getEventType().name())).append("</b>\n");
        sb.append("account: ").append(ev.getExchangeAccountId());
        if (ev.getSignalId() != null) {
            sb.append("\nsignal: ").append(escape(ev.getSignalId()));
        }
        if (ev.getExecutedTradeId() != null) {
            sb.append("\ntrade: ").append(ev.getExecutedTradeId());
        }
        Map<String, Object> meta = ev.getMetadata();
        if (meta != null && !meta.isEmpty()) {
            meta.forEach((k, v) -> sb.append("\n").append(escape(k)).append(": ").append(escape(String.valueOf(v))));
        }
        return sb.toString();
    }

    private static String emojiFor(ExecutionEvent ev) {
        return switch (ev.getEventType()) {
            case ORDER_FILLED, ORDER_PLACED -> "✅";              // ✅
            case POSITION_CLOSED, RECONCILE_CLOSED_EXTERNALLY -> "🏁"; // 🏁
            case ORDER_REJECTED, AUTH_FAILURE, BYBIT_CIRCUIT_OPEN -> "⛔"; // ⛔
            case KILL_SWITCH_TOGGLED, DAILY_HALT_ENTERED -> "🛑"; // 🛑
            case TRAIL_UPDATED -> "🔼";                     // 🔼
            default -> "ℹ️";                                // ℹ️
        };
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Outcome of a send attempt. {@code error} is null on success. */
    public record SendResult(boolean ok, String error) {
        static SendResult success() { return new SendResult(true, null); }
        static SendResult fail(String error) { return new SendResult(false, error); }
    }
}
