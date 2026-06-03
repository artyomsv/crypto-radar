package com.cryptoradar.execution.notify;

import com.cryptoradar.execution.intake.ExecutionSettingsService;
import com.cryptoradar.execution.intake.ExecutionSettingsService.TelegramRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to the {@code crypto:options:opportunities} Redis channel that
 * options-service publishes a row to whenever a fresh option setup clears its
 * confidence threshold (and its dedup cooldown). When the user has opted into
 * option-opportunity alerts, each message is formatted and pushed to Telegram.
 *
 * <p>Mirrors {@link com.cryptoradar.execution.intake.SignalSubscriber}'s
 * connect/reconnect pattern. The bot token never leaves this service — it lives
 * in {@link ExecutionSettingsService}'s in-memory runtime cache and is read here
 * only at send time.
 */
@ApplicationScoped
public class OptionOpportunitySubscriber {

    private static final Logger LOG = Logger.getLogger(OptionOpportunitySubscriber.class);
    private static final String CHANNEL = "crypto:options:opportunities";
    private static final int CONNECT_DELAY_SECONDS = 4;
    private static final int RECONNECT_DELAY_SECONDS = 10;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "option-opportunity-reconnect");
                t.setDaemon(true);
                return t;
            });

    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @Inject ExecutionSettingsService settings;
    @Inject TelegramNotifier notifier;

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    String redisHosts;

    void onStart(@Observes StartupEvent event) {
        SCHEDULER.schedule(this::connect, CONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    void connect() {
        RedisOptions options = new RedisOptions().setConnectionString(redisHosts);
        Redis.createClient(vertx, options).connect().subscribe().with(
                this::setupSubscription,
                err -> {
                    LOG.errorf(err, "OptionOpportunitySubscriber Redis connect failed - retry in %ds",
                            RECONNECT_DELAY_SECONDS);
                    SCHEDULER.schedule(this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
                });
    }

    void setupSubscription(RedisConnection conn) {
        LOG.infof("OptionOpportunitySubscriber subscribed to %s", CHANNEL);
        conn.handler(resp -> {
            if (resp == null || resp.size() < 3) return;
            if (!"message".equals(resp.get(0).toString())) return;
            String payload = resp.get(2).toString();
            vertx.executeBlocking(() -> {
                onMessage(payload);
                return null;
            }).subscribe().with(v -> {}, err -> LOG.errorf(err, "option opportunity handler error"));
        });
        conn.send(io.vertx.mutiny.redis.client.Request.cmd(io.vertx.mutiny.redis.client.Command.SUBSCRIBE).arg(CHANNEL))
                .subscribe().with(
                        v -> {},
                        err -> LOG.errorf(err, "SUBSCRIBE %s failed", CHANNEL));
    }

    /** Exposed for unit tests — accepts a raw opportunity payload. */
    public void onMessage(String json) {
        TelegramRuntime rt = settings.telegramRuntime();
        if (!rt.enabled() || !rt.notifyOptions() || !rt.isSendable()) return;
        try {
            JsonNode n = mapper.readTree(json);
            String text = format(n);
            TelegramNotifier.SendResult result = notifier.send(rt.botToken(), rt.chatId(), text);
            if (!result.ok()) {
                LOG.warnf("telegram_option_notify_failed underlying=%s error=%s",
                        n.path("underlying").asText("?"), result.error());
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.warnf(e, "failed to handle option opportunity payload");
        }
    }

    private static String format(JsonNode n) {
        String underlying = n.path("underlying").asText("?");
        double strikeCall = n.path("strikeCall").asDouble();
        double strikePut = n.path("strikePut").asDouble();
        String kind = Math.abs(strikeCall - strikePut) < 1e-9 ? "straddle" : "strangle";

        StringBuilder sb = new StringBuilder();
        sb.append("📈 <b>Option setup: ").append(escape(underlying)).append("</b>\n");
        sb.append("expiry ").append(escape(n.path("expiry").asText("?")))
          .append(" · confidence ").append(Math.round(n.path("confidence").asDouble())).append("/100\n");
        sb.append(kind).append(" premium $").append(fmt(n.path("premium").asDouble()));
        appendVol(sb, n);
        sb.append("\ncall ").append(fmt(strikeCall)).append(" · put ").append(fmt(strikePut));
        sb.append("\n<code>").append(escape(n.path("callSymbol").asText("")))
          .append("</code>\n<code>").append(escape(n.path("putSymbol").asText(""))).append("</code>");
        return sb.toString();
    }

    private static void appendVol(StringBuilder sb, JsonNode n) {
        JsonNode iv = n.path("impliedVolAtm");
        JsonNode rv = n.path("realizedVol14d");
        if (iv.isNumber() && rv.isNumber()) {
            sb.append("\nIV ").append(Math.round(iv.asDouble())).append("% vs RV14 ")
              .append(Math.round(rv.asDouble())).append("%");
            JsonNode gap = n.path("ivRvSpread");
            if (gap.isNumber()) {
                sb.append(" (gap ").append(gap.asDouble() >= 0 ? "+" : "")
                  .append(Math.round(gap.asDouble())).append("%)");
            }
        }
    }

    /**
     * Magnitude-aware price formatter — mirrors {@code formatPrice} in the
     * frontend's optionsDerivations.ts. Crypto prices span BTC (~$68 000) to
     * DOGE (~$0.10) and strikes down to ~$0.098; a fixed {@code %.2f} collapses
     * low-priced tokens (0.098 → "0.10", tiny premiums → "0.00"). Decimals are
     * picked from the magnitude, then trailing zeros trimmed.
     */
    private static String fmt(double v) {
        if (v == 0) return "0";
        double abs = Math.abs(v);
        int digits;
        if (abs >= 1000) digits = 0;
        else if (abs >= 1) digits = 2;
        else if (abs >= 0.1) digits = 4;
        else if (abs >= 0.001) digits = 5;
        else digits = 8;
        String s = String.format(java.util.Locale.ROOT, "%." + digits + "f", v);
        return s.contains(".") ? s.replaceFirst("\\.?0+$", "") : s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
