package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Vector E — exchange-side companion to {@code OutcomeEvaluator}'s
 * stagnation exit. Where the signal-service side closes the tracked
 * {@code signal_outcomes} row for measurement, this class closes the
 * real Bybit position so live capital is not bled by a trade that
 * has sat in a narrow MFE/MAE band for longer than the configured
 * window.
 *
 * <p>MFE/MAE are sourced from {@code signal_outcomes} via the trade's
 * {@code signal_id} link. The outcome evaluator already maintains
 * authoritative MFE/MAE on a 60s scheduler; this class reads rather
 * than recomputes, so a threshold tweak propagates from one place.
 *
 * <p><b>Default disabled.</b> Gating is off unless the operator opts
 * in via {@code execution.stagnation-monitor.enabled=true}. Enabling
 * sends market orders to close positions — not a move to make without
 * first observing the tracking-side STAGNATION reasons land
 * sensibly in {@code signal_outcomes}.
 */
@ApplicationScoped
public class StagnationMonitor {

    private static final Logger LOG = Logger.getLogger(StagnationMonitor.class);

    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject OrderPlacer orderPlacer;
    @Inject EntityManager entityManager;
    @Inject StrategyExitPolicy exitPolicy;

    @ConfigProperty(name = "execution.stagnation-monitor.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "execution.stagnation-monitor.min-age-minutes", defaultValue = "45")
    int minAgeMinutes;

    @ConfigProperty(name = "execution.stagnation-monitor.mfe-threshold-pct", defaultValue = "0.2")
    double mfeThresholdPct;

    @ConfigProperty(name = "execution.stagnation-monitor.mae-floor-pct", defaultValue = "-0.3")
    double maeFloorPct;

    @Scheduled(every = "60s", delayed = "45s")
    @Transactional
    public void sweep() {
        if (!enabled) return;

        List<ExchangeAccount> accounts = accountRepo.listAll();
        if (accounts.isEmpty()) return;

        Instant ageThreshold = Instant.now().minus(minAgeMinutes, ChronoUnit.MINUTES);
        int closed = 0;
        for (ExchangeAccount account : accounts) {
            closed += sweepForAccount(account, ageThreshold);
        }
        if (closed > 0) {
            LOG.infof("StagnationMonitor closed %d position(s) across %d account(s)",
                    closed, accounts.size());
        }
    }

    int sweepForAccount(ExchangeAccount account, Instant ageThreshold) {
        List<ExecutedTrade> open = tradeRepo.findOpenForAccount(account.getId());
        if (open.isEmpty()) return 0;

        int closed = 0;
        for (ExecutedTrade trade : open) {
            if (exitPolicy.isLongHorizon(trade.getStrategy())) continue;
            if (trade.getOpenedAt() == null) continue;
            if (trade.getOpenedAt().isAfter(ageThreshold)) continue;
            if (trade.getSignalId() == null) continue;
            if (isStagnant(trade)) {
                closeStagnant(account, trade);
                closed++;
            }
        }
        return closed;
    }

    private boolean isStagnant(ExecutedTrade trade) {
        Optional<Excursion> ex = fetchExcursion(trade.getSignalId());
        return ex.map(this::isStagnant).orElse(false);
    }

    // Pure decision. Package-private so unit tests exercise it without
    // a stubbed DB. Stagnant when the trade has neither made real
    // progress (MFE below threshold) nor taken real damage (MAE above
    // floor) — fees are the only thing eating equity.
    boolean isStagnant(Excursion e) {
        return e.mfePct() < mfeThresholdPct && e.maePct() > maeFloorPct;
    }

    @SuppressWarnings("unchecked")
    private Optional<Excursion> fetchExcursion(String signalId) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery(
                            "SELECT max_favorable_pct, max_adverse_pct "
                            + "FROM signal_outcomes WHERE signal_id = :signalId "
                            + "AND status = 'PENDING' LIMIT 1")
                    .setParameter("signalId", signalId)
                    .getResultList();
            if (rows.isEmpty()) return Optional.empty();
            Object[] row = rows.get(0);
            Double mfe = toDouble(row[0]);
            Double mae = toDouble(row[1]);
            if (mfe == null || mae == null) return Optional.empty();
            return Optional.of(new Excursion(mfe, mae));
        } catch (RuntimeException e) {
            LOG.warnf(e, "StagnationMonitor excursion query failed for %s — failing open", signalId);
            return Optional.empty();
        }
    }

    private void closeStagnant(ExchangeAccount account, ExecutedTrade trade) {
        Duration age = Duration.between(Objects.requireNonNull(trade.getOpenedAt()), Instant.now());
        LOG.infof("STAGNATION_CLOSE account=%d trade=%d %s %s age=%dmin",
                account.getId(), trade.getId(),
                trade.getSymbol(), trade.getDirection(), age.toMinutes());
        orderPlacer.close(account, trade, ExitReason.STAGNATION);
    }

    private static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        return null;
    }

    /** Snapshot of the excursion values used to evaluate stagnation. */
    record Excursion(double mfePct, double maePct) {}
}
