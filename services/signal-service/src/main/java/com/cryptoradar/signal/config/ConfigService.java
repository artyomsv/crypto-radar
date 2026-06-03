package com.cryptoradar.signal.config;

import com.cryptoradar.signal.backtest.BacktestService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot-reloadable signal engine configuration.
 *
 * <p>Holds an {@link AtomicReference} to the active {@link SignalConfig}
 * so {@code SignalEngine} can call {@link #getActive()} on every
 * {@code computeSignal} invocation without touching the database.
 *
 * <p>The reload scheduler runs every 30 s and swaps the reference only when
 * the active version ID has changed, keeping the hot path allocation-free on
 * most ticks.
 */
@ApplicationScoped
public class ConfigService {

    private static final Logger LOG = Logger.getLogger(ConfigService.class);

    private static final double WEIGHT_SUM_TOLERANCE = 0.001;
    private static final double SCORE_MIN = -100.0;
    private static final double SCORE_MAX = 100.0;

    private static final int AUTO_BACKTEST_DAYS = 30;

    private final ConfigVersionRepository repository;
    private final BacktestService backtestService;
    private final AtomicReference<SignalConfig> activeConfig = new AtomicReference<>();
    private final AtomicLong activeVersionId = new AtomicLong(-1L);

    public ConfigService(ConfigVersionRepository repository, BacktestService backtestService) {
        this.repository = repository;
        this.backtestService = backtestService;
    }

    void onStart(@Observes StartupEvent event) {
        reload();
    }

    /**
     * Polls the DB for an active config change. No-op when the active ID
     * hasn't changed since the last tick, so the hot path stays DB-free.
     */
    @Scheduled(every = "30s")
    @Transactional
    public void reload() {
        repository.findActive().ifPresentOrElse(
                this::applyIfChanged,
                () -> LOG.warn("No active signal config found — engine using last known config or defaults")
        );
    }

    /**
     * Returns the current cached config snapshot. Never touches the DB.
     * Callers should handle null defensively (only possible during first-tick
     * before startup finishes if the DB has no active row).
     */
    public SignalConfig getActive() {
        return activeConfig.get();
    }

    /**
     * Validates and inserts a new immutable config version. The new version is
     * NOT activated — call {@link #activateVersion} explicitly.
     *
     * @throws IllegalArgumentException if validation fails
     */
    @Transactional
    public SignalConfigVersion saveVersion(SignalConfig config, String description, Long parentVersionId) {
        validate(config);

        SignalConfigVersion entity = new SignalConfigVersion();
        entity.setVersion(repository.nextVersionNumber());
        entity.setConfig(config);
        entity.setDescription(description != null ? description : "");
        entity.setParentVersionId(parentVersionId);
        entity.setIsActive(false);
        repository.persist(entity);

        triggerAutoBacktest(entity.getId());
        return entity;
    }

    /**
     * Fires a Tier 1 backtest over the last 30 days immediately after a new
     * version is saved. Fail-open: a backtest failure must never block the
     * config save itself.
     */
    private void triggerAutoBacktest(Long newVersionId) {
        if (newVersionId == null) {
            return;
        }
        try {
            Instant end = Instant.now();
            Instant start = end.minus(AUTO_BACKTEST_DAYS, ChronoUnit.DAYS);
            backtestService.runTier1(newVersionId, start, end);
        } catch (Exception e) {
            LOG.warnf(e, "Auto-backtest failed for config version %d — save succeeded", newVersionId);
        }
    }

    /**
     * Atomically swaps the active version. Uses a native UPDATE so exactly
     * one row has {@code is_active = true} at all times, enforced by the
     * partial unique index.
     *
     * @throws jakarta.ws.rs.NotFoundException if the id does not exist
     */
    @Transactional
    public SignalConfigVersion activateVersion(long id) {
        SignalConfigVersion target = repository.findById(id)
                .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("Config version not found: " + id));

        // Two statements: PostgreSQL's partial unique index on is_active is
        // evaluated per row inside a single UPDATE, so a single-statement
        // "SET is_active = (id = ?)" violates uniqueness transiently when the
        // new active is set before the previous active is cleared. Clearing
        // the previous active first guarantees the index sees zero true rows
        // between statements within the same transaction.
        repository.getEntityManager()
                .createNativeQuery(
                        "UPDATE signal_config_versions SET is_active = false WHERE is_active = true AND id <> :id")
                .setParameter("id", id)
                .executeUpdate();

        repository.getEntityManager()
                .createNativeQuery(
                        "UPDATE signal_config_versions SET is_active = true WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();

        target.setIsActive(true);
        applyIfChanged(target);
        return target;
    }

    /**
     * Validates a {@link SignalConfig} before persistence.
     *
     * @throws IllegalArgumentException with an actionable message on failure
     */
    void validate(SignalConfig config) {
        validateWeights(config.weights());
        validateScores(config);
        validateMonotonicThresholds(config);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void applyIfChanged(SignalConfigVersion version) {
        long incomingId = version.getId();
        long currentId = activeVersionId.get();
        if (incomingId == currentId) {
            return;
        }
        SignalConfig incoming = version.getConfig();
        activeConfig.set(incoming);
        activeVersionId.set(incomingId);
        logReload(currentId, incomingId, version.getVersion());
    }

    private void logReload(long fromId, long toId, int toVersion) {
        MDC.put("event", "config_reload");
        MDC.put("fromVersion", String.valueOf(fromId));
        MDC.put("toVersion", String.valueOf(toId));
        try {
            LOG.infof("Signal config reloaded: version %d (id=%d)", toVersion, toId);
        } finally {
            MDC.remove("event");
            MDC.remove("fromVersion");
            MDC.remove("toVersion");
        }
    }

    private void validateWeights(SignalConfig.Weights weights) {
        double sum = weights.sum();
        if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    String.format("weights sum to %.4f, must be 1.0 ±%.3f", sum, WEIGHT_SUM_TOLERANCE));
        }
    }

    private void validateScores(SignalConfig config) {
        validateScoreRange("rsi.scoreOversoldExtreme", config.rsi().scoreOversoldExtreme());
        validateScoreRange("rsi.scoreOversoldApproaching", config.rsi().scoreOversoldApproaching());
        validateScoreRange("rsi.scoreOverboughtApproaching", config.rsi().scoreOverboughtApproaching());
        validateScoreRange("rsi.scoreOverboughtExtreme", config.rsi().scoreOverboughtExtreme());
        validateScoreRange("macd.scoreBullish", config.macd().scoreBullish());
        validateScoreRange("macd.scoreBearish", config.macd().scoreBearish());
        validateScoreRange("sma200.scoreAbove", config.sma200().scoreAbove());
        validateScoreRange("sma200.scoreBelow", config.sma200().scoreBelow());
        validateScoreRange("bollinger.scoreLower", config.bollinger().scoreLower());
        validateScoreRange("bollinger.scoreUpper", config.bollinger().scoreUpper());
        validateScoreRange("supportResistance.scoreNearSupport", config.supportResistance().scoreNearSupport());
        validateScoreRange("supportResistance.scoreNearResistance", config.supportResistance().scoreNearResistance());
        validateScoreRange("derivativesFunding.scoreModerate", config.derivativesFunding().scoreModerate());
        validateScoreRange("derivativesFunding.scoreStrong", config.derivativesFunding().scoreStrong());
        validateScoreRange("derivativesFunding.scoreExtreme", config.derivativesFunding().scoreExtreme());
        validateScoreRange("longShortRatio.scoreModerate", config.longShortRatio().scoreModerate());
        validateScoreRange("longShortRatio.scoreExtreme", config.longShortRatio().scoreExtreme());
        validateScoreRange("fearGreed.scoreModerate", config.fearGreed().scoreModerate());
        validateScoreRange("fearGreed.scoreExtreme", config.fearGreed().scoreExtreme());
        validateScoreRange("orderBook.scoreHighVolatility", config.orderBook().scoreHighVolatility());
    }

    private void validateScoreRange(String field, double value) {
        if (value < SCORE_MIN || value > SCORE_MAX) {
            throw new IllegalArgumentException(
                    String.format("%s is %.1f, must be in [%.0f, %.0f]", field, value, SCORE_MIN, SCORE_MAX));
        }
    }

    private void validateMonotonicThresholds(SignalConfig config) {
        validateRsiMonotonic(config.rsi());
        validateBollingerMonotonic(config.bollinger());
        validateDerivativesFundingMonotonic(config.derivativesFunding());
        validateLongShortRatioMonotonic(config.longShortRatio());
        validateFearGreedMonotonic(config.fearGreed());
        validateTrail(config.trail());
    }

    private void validateTrail(SignalConfig.Trail trail) {
        if (trail == null) {
            throw new IllegalArgumentException("trail config is required");
        }
        if (trail.activationR() <= 0) {
            throw new IllegalArgumentException(
                    String.format("trail.activationR (%.3f) must be > 0", trail.activationR()));
        }
        if (trail.stepR() <= 0) {
            throw new IllegalArgumentException(
                    String.format("trail.stepR (%.3f) must be > 0", trail.stepR()));
        }
        if (trail.offsetR() < 0) {
            throw new IllegalArgumentException(
                    String.format("trail.offsetR (%.3f) must be >= 0", trail.offsetR()));
        }
        if (trail.widerOffsetActivationR() < 0 || trail.widerOffsetR() < 0) {
            throw new IllegalArgumentException(
                    "trail.widerOffsetActivationR and widerOffsetR must be >= 0 (0 disables second rung)");
        }
        if (trail.widerOffsetActivationR() > 0
                && trail.widerOffsetActivationR() < trail.activationR()) {
            throw new IllegalArgumentException(String.format(
                    "trail.widerOffsetActivationR (%.3f) must be >= activationR (%.3f)",
                    trail.widerOffsetActivationR(), trail.activationR()));
        }
    }

    private void validateRsiMonotonic(SignalConfig.Rsi rsi) {
        if (rsi.oversoldExtreme() >= rsi.oversoldApproaching()) {
            throw new IllegalArgumentException(
                    String.format("rsi.oversoldExtreme (%.1f) must be < oversoldApproaching (%.1f)",
                            rsi.oversoldExtreme(), rsi.oversoldApproaching()));
        }
        if (rsi.oversoldApproaching() >= rsi.overboughtApproaching()) {
            throw new IllegalArgumentException(
                    String.format("rsi.oversoldApproaching (%.1f) must be < overboughtApproaching (%.1f)",
                            rsi.oversoldApproaching(), rsi.overboughtApproaching()));
        }
        if (rsi.overboughtApproaching() >= rsi.overboughtExtreme()) {
            throw new IllegalArgumentException(
                    String.format("rsi.overboughtApproaching (%.1f) must be < overboughtExtreme (%.1f)",
                            rsi.overboughtApproaching(), rsi.overboughtExtreme()));
        }
    }

    private void validateBollingerMonotonic(SignalConfig.Bollinger b) {
        if (b.lowerPosition() >= b.upperPosition()) {
            throw new IllegalArgumentException(
                    String.format("bollinger.lowerPosition (%.2f) must be < upperPosition (%.2f)",
                            b.lowerPosition(), b.upperPosition()));
        }
    }

    private void validateDerivativesFundingMonotonic(SignalConfig.DerivativesFunding d) {
        if (d.neutralThreshold() >= d.moderateThreshold()) {
            throw new IllegalArgumentException(
                    String.format("derivativesFunding.neutralThreshold (%.4f) must be < moderateThreshold (%.4f)",
                            d.neutralThreshold(), d.moderateThreshold()));
        }
        if (d.moderateThreshold() >= d.extremeThreshold()) {
            throw new IllegalArgumentException(
                    String.format("derivativesFunding.moderateThreshold (%.4f) must be < extremeThreshold (%.4f)",
                            d.moderateThreshold(), d.extremeThreshold()));
        }
    }

    private void validateLongShortRatioMonotonic(SignalConfig.LongShortRatio ls) {
        if (ls.extremelyCrowdedShortsPct() >= ls.crowdedShortsPct()) {
            throw new IllegalArgumentException(
                    String.format("longShortRatio.extremelyCrowdedShortsPct (%.1f) must be < crowdedShortsPct (%.1f)",
                            ls.extremelyCrowdedShortsPct(), ls.crowdedShortsPct()));
        }
        if (ls.crowdedShortsPct() >= ls.crowdedLongsPct()) {
            throw new IllegalArgumentException(
                    String.format("longShortRatio.crowdedShortsPct (%.1f) must be < crowdedLongsPct (%.1f)",
                            ls.crowdedShortsPct(), ls.crowdedLongsPct()));
        }
        if (ls.crowdedLongsPct() >= ls.extremelyCrowdedLongsPct()) {
            throw new IllegalArgumentException(
                    String.format("longShortRatio.crowdedLongsPct (%.1f) must be < extremelyCrowdedLongsPct (%.1f)",
                            ls.crowdedLongsPct(), ls.extremelyCrowdedLongsPct()));
        }
    }

    private void validateFearGreedMonotonic(SignalConfig.FearGreed fg) {
        if (fg.extremeFearMax() >= fg.fearMax()) {
            throw new IllegalArgumentException(
                    String.format("fearGreed.extremeFearMax (%d) must be < fearMax (%d)",
                            fg.extremeFearMax(), fg.fearMax()));
        }
        if (fg.fearMax() >= fg.greedMin()) {
            throw new IllegalArgumentException(
                    String.format("fearGreed.fearMax (%d) must be < greedMin (%d)",
                            fg.fearMax(), fg.greedMin()));
        }
        if (fg.greedMin() >= fg.extremeGreedMin()) {
            throw new IllegalArgumentException(
                    String.format("fearGreed.greedMin (%d) must be < extremeGreedMin (%d)",
                            fg.greedMin(), fg.extremeGreedMin()));
        }
    }
}
