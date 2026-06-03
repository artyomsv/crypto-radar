package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExecutionSettings;
import com.cryptoradar.execution.repository.ExecutionSettingsRepository;
import com.cryptoradar.execution.security.CredentialCipher;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Hot-reloadable execution-gate parameters. Mirrors the SignalConfig pattern:
 * one singleton DB row, cached in an AtomicReference, refreshed every 30s.
 * Callers read via {@link #snapshot()} which never touches the DB on the hot
 * path.
 *
 * <p>Each call site (SignalSubscriber, SymbolPerformanceGate, ...) was
 * previously bound to its own {@code @ConfigProperty}; this service unifies
 * them so the UI can PATCH a single endpoint and every gate sees the new
 * value within one scheduler tick.
 *
 * <p>Also owns the Telegram notification config. The bot token is stored
 * AES-GCM-encrypted at rest and decrypted into the in-memory
 * {@link #telegramCache} at reload/update time so the notify path never has to
 * touch the DB or re-decrypt. The decrypted token is held only here, never
 * placed on the {@link Snapshot} returned to the API, and never logged.
 */
@ApplicationScoped
public class ExecutionSettingsService {

    private static final Logger LOG = Logger.getLogger(ExecutionSettingsService.class);

    /** High-value events notified by default until the user customises the set. */
    public static final List<String> DEFAULT_NOTIFIED_EVENTS = List.of(
            ExecutionEventType.ORDER_FILLED.name(),
            ExecutionEventType.POSITION_CLOSED.name(),
            ExecutionEventType.RECONCILE_CLOSED_EXTERNALLY.name(),
            ExecutionEventType.ORDER_REJECTED.name(),
            ExecutionEventType.KILL_SWITCH_TOGGLED.name(),
            ExecutionEventType.DAILY_HALT_ENTERED.name(),
            ExecutionEventType.AUTH_FAILURE.name(),
            ExecutionEventType.BYBIT_CIRCUIT_OPEN.name());

    /**
     * Public, API-safe view of the settings. {@code telegramBotToken} is
     * write-only: the PUT body may carry a new plaintext token, but
     * {@link #toSnapshot} always returns it as {@code null} so the secret never
     * leaves the service. {@code telegramConfigured} signals to the UI whether a
     * token is stored without revealing it.
     */
    public record Snapshot(
            int alignmentFloor,
            boolean symbolGateEnabled,
            int symbolGateLookback,
            double symbolGateThresholdR,
            int symbolGateCacheTtlSec,
            boolean confluenceTrendRequired,
            int confluenceWindowMinutes,
            int dailyPnlEquityCacheTtlSec,
            boolean telegramEnabled,
            String telegramChatId,
            List<String> telegramNotifiedEvents,
            boolean telegramConfigured,
            String telegramBotToken,
            boolean telegramNotifyOptions
    ) {
        public static Snapshot defaults() {
            return new Snapshot(70, true, 10, -3.0, 30, true, 15, 60,
                    false, null, DEFAULT_NOTIFIED_EVENTS, false, null, false);
        }
    }

    /** Decrypted Telegram config — in-memory only, never serialized or logged. */
    public record TelegramRuntime(boolean enabled, String botToken, String chatId,
                                  Set<String> events, boolean notifyOptions) {
        public boolean isSendable() {
            return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        }
    }

    private final ExecutionSettingsRepository repository;
    private final CredentialCipher cipher;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>(Snapshot.defaults());
    private final AtomicReference<TelegramRuntime> telegramCache =
            new AtomicReference<>(new TelegramRuntime(false, null, null, Set.copyOf(DEFAULT_NOTIFIED_EVENTS), false));

    public ExecutionSettingsService(ExecutionSettingsRepository repository, CredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    void onStart(@Observes StartupEvent event) {
        reload();
    }

    @Scheduled(every = "30s")
    @Transactional
    public void reload() {
        // Fail-open: Quarkus test profiles that deactivate Hibernate
        // (OrderPlacerTest et al.) would otherwise NPE on the repository
        // call at startup. Keep the cached defaults instead.
        try {
            repository.findSingleton().ifPresent(this::applyRow);
        } catch (RuntimeException e) {
            LOG.warnf("execution_settings reload skipped (DB unavailable: %s)", e.getMessage());
        }
    }

    public Snapshot snapshot() {
        return cached.get();
    }

    /** Decrypted Telegram config for the notify/test path. Never null. */
    public TelegramRuntime telegramRuntime() {
        return telegramCache.get();
    }

    @Transactional
    public Snapshot update(Snapshot incoming) {
        validate(incoming);
        ExecutionSettings row = repository.findSingleton().orElseGet(() -> {
            ExecutionSettings fresh = new ExecutionSettings();
            repository.persist(fresh);
            return fresh;
        });
        row.setAlignmentFloor(incoming.alignmentFloor());
        row.setSymbolGateEnabled(incoming.symbolGateEnabled());
        row.setSymbolGateLookback(incoming.symbolGateLookback());
        row.setSymbolGateThresholdR(incoming.symbolGateThresholdR());
        row.setSymbolGateCacheTtlSec(incoming.symbolGateCacheTtlSec());
        row.setConfluenceTrendRequired(incoming.confluenceTrendRequired());
        row.setConfluenceWindowMinutes(incoming.confluenceWindowMinutes());
        row.setDailyPnlEquityCacheTtlSec(incoming.dailyPnlEquityCacheTtlSec());

        row.setTelegramEnabled(incoming.telegramEnabled());
        row.setTelegramChatId(blankToNull(incoming.telegramChatId()));
        row.setTelegramNotifiedEvents(eventsToCsv(incoming.telegramNotifiedEvents()));
        row.setTelegramNotifyOptions(incoming.telegramNotifyOptions());
        // Only overwrite the stored token when a new plaintext is supplied;
        // an empty/absent token field means "leave the saved one untouched".
        String newToken = incoming.telegramBotToken();
        if (newToken != null && !newToken.isBlank()) {
            row.setTelegramBotTokenEnc(cipher.encrypt(newToken.trim()));
        }

        Snapshot next = applyRow(row);
        LOG.infof("execution_settings_updated alignmentFloor=%d symbolGate(enabled=%s thresholdR=%.2f lookback=%d) confluence(required=%s windowMin=%d) telegram(enabled=%s configured=%s events=%d)",
                next.alignmentFloor(), next.symbolGateEnabled(), next.symbolGateThresholdR(),
                next.symbolGateLookback(), next.confluenceTrendRequired(), next.confluenceWindowMinutes(),
                next.telegramEnabled(), next.telegramConfigured(), next.telegramNotifiedEvents().size());
        return next;
    }

    /** Decrypts the token, refreshes both caches, returns the API-safe snapshot. */
    private Snapshot applyRow(ExecutionSettings row) {
        String decryptedToken = decryptQuietly(row.getTelegramBotTokenEnc());
        Set<String> events = csvToEvents(row.getTelegramNotifiedEvents());
        telegramCache.set(new TelegramRuntime(
                Boolean.TRUE.equals(row.getTelegramEnabled()),
                decryptedToken,
                row.getTelegramChatId(),
                events,
                Boolean.TRUE.equals(row.getTelegramNotifyOptions())));
        Snapshot next = toSnapshot(row, events);
        cached.set(next);
        return next;
    }

    private static Snapshot toSnapshot(ExecutionSettings row, Set<String> events) {
        return new Snapshot(
                row.getAlignmentFloor(),
                row.getSymbolGateEnabled(),
                row.getSymbolGateLookback(),
                row.getSymbolGateThresholdR(),
                row.getSymbolGateCacheTtlSec(),
                row.getConfluenceTrendRequired(),
                row.getConfluenceWindowMinutes(),
                row.getDailyPnlEquityCacheTtlSec(),
                Boolean.TRUE.equals(row.getTelegramEnabled()),
                row.getTelegramChatId(),
                List.copyOf(events),
                row.getTelegramBotTokenEnc() != null && !row.getTelegramBotTokenEnc().isBlank(),
                null,
                Boolean.TRUE.equals(row.getTelegramNotifyOptions()));
    }

    private String decryptQuietly(String enc) {
        if (enc == null || enc.isBlank()) return null;
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            LOG.warn("telegram_bot_token decrypt failed — notifications disabled until re-saved");
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * NULL input means "never configured" → keep defaults on read. A non-null
     * but empty selection is stored as an empty string so the user can mute all
     * events without it springing back to defaults (the empty/null distinction
     * is the whole point — see {@link #csvToEvents}).
     */
    private static String eventsToCsv(List<String> events) {
        if (events == null) return null;
        return events.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private static Set<String> csvToEvents(String csv) {
        if (csv == null) return Set.copyOf(DEFAULT_NOTIFIED_EVENTS); // never configured
        if (csv.isBlank()) return Set.of();                          // explicitly muted
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static void validate(Snapshot s) {
        if (s.alignmentFloor() < 0 || s.alignmentFloor() > 100) {
            throw new IllegalArgumentException("alignmentFloor must be in [0,100], got " + s.alignmentFloor());
        }
        if (s.symbolGateLookback() < 1) {
            throw new IllegalArgumentException("symbolGateLookback must be >= 1, got " + s.symbolGateLookback());
        }
        if (s.symbolGateCacheTtlSec() < 1) {
            throw new IllegalArgumentException("symbolGateCacheTtlSec must be >= 1, got " + s.symbolGateCacheTtlSec());
        }
        if (s.confluenceWindowMinutes() < 1) {
            throw new IllegalArgumentException("confluenceWindowMinutes must be >= 1, got " + s.confluenceWindowMinutes());
        }
        if (s.dailyPnlEquityCacheTtlSec() < 1) {
            throw new IllegalArgumentException("dailyPnlEquityCacheTtlSec must be >= 1, got " + s.dailyPnlEquityCacheTtlSec());
        }
        if (s.telegramEnabled() && (s.telegramChatId() == null || s.telegramChatId().isBlank())) {
            throw new IllegalArgumentException("telegramChatId is required when telegramEnabled is true");
        }
        if (s.telegramNotifiedEvents() != null) {
            for (String ev : s.telegramNotifiedEvents()) {
                if (!isKnownEventType(ev)) {
                    throw new IllegalArgumentException("unknown event type: " + ev);
                }
            }
        }
    }

    private static boolean isKnownEventType(String name) {
        try {
            ExecutionEventType.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
