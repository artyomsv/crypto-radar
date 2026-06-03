package com.cryptoradar.execution.intake;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Vector B — trend-continuation confluence gate. Phase 2 analysis showed
 * trend-continuation LONG entries (BUY + STRONG_BUY) lost a combined
 * −17.14R across 73 trades while dimension-scoring on the same window
 * was break-even (+0.30R). The detector fires on momentum pullbacks but
 * Phase-2 BULL conditions produced longs at local tops.
 *
 * <p>The gate requires a recent open {@code dimension-scoring} outcome
 * in the same direction before a trend-continuation entry dispatches.
 * The intent: the two signal sources must agree within a short window —
 * momentum alone is not enough. Applied to BOTH directions: in v4 the
 * SHORT-gap fix unblocked SELL signals, and the first 4 v4 SHORTs all
 * lost. Mirroring the LONG confluence rule symmetrically since
 * trend-continuation SHORTs in BULL hit the same "counter-trend" trap
 * trend-continuation LONGs hit at local tops.
 *
 * <p>Uses the same shared-DB native read as {@link SymbolPerformanceGate}.
 * Query errors fail-open (log WARN, allow dispatch) since the gate is
 * enrichment, not safety-critical.
 */
@ApplicationScoped
public class DetectorConfluenceCheck {

    private static final Logger LOG = Logger.getLogger(DetectorConfluenceCheck.class);

    private static final String STRATEGY_TREND_CONTINUATION = "trend-continuation";
    private static final String STRATEGY_DIMENSION_SCORING = "dimension-scoring";
    private static final String STATUS_PENDING = "PENDING";

    @Inject
    EntityManager entityManager;

    @Inject
    ExecutionSettingsService executionSettings;

    public boolean requiresConfluence(String strategy, String direction) {
        if (!executionSettings.snapshot().confluenceTrendRequired()) return false;
        return STRATEGY_TREND_CONTINUATION.equals(strategy);
    }

    public boolean hasConfluence(String symbol, String direction) {
        try {
            int windowMinutes = executionSettings.snapshot().confluenceWindowMinutes();
            Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
            Object result = entityManager.createNativeQuery(
                            "SELECT EXISTS("
                            + "  SELECT 1 FROM signal_outcomes"
                            + "  WHERE symbol = :symbol"
                            + "    AND direction = :direction"
                            + "    AND strategy = :strategy"
                            + "    AND status = :status"
                            + "    AND fired_at > :since"
                            + ")")
                    .setParameter("symbol", symbol)
                    .setParameter("direction", direction)
                    .setParameter("strategy", STRATEGY_DIMENSION_SCORING)
                    .setParameter("status", STATUS_PENDING)
                    .setParameter("since", since)
                    .getSingleResult();
            return result instanceof Boolean b && b;
        } catch (RuntimeException e) {
            LOG.warnf(e, "DetectorConfluenceCheck query failed for %s %s — failing open", symbol, direction);
            return true;
        }
    }
}
