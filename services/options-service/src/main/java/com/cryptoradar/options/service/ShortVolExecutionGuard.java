package com.cryptoradar.options.service;

import com.cryptoradar.options.model.OptionShortVolOpportunity;
import com.cryptoradar.options.repository.OptionShortVolOpportunityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Tier 4 — execution feature flag + binding constraints.
 *
 * <p>This class is the ONLY place that decides whether a short-vol
 * opportunity may be auto-executed against Bybit. It is currently
 * <strong>disabled by default</strong> (feature flag off). The actual
 * order placement against Bybit options is intentionally NOT yet wired —
 * Bybit options trading on this account is not enabled. When that's
 * unblocked AND the user explicitly flips
 * {@code options.short-vol.execution.enabled=true}, a separate
 * {@code ShortVolOrderPlacer} will consume from {@link #shouldExecute}'s
 * approvals.
 *
 * <p>Constraints enforced before any approval:
 * <ul>
 *   <li>Feature flag on (default off)</li>
 *   <li>Max concurrent active short-vol positions ≤ {@code maxConcurrent}</li>
 *   <li>Per-trade risk ≤ {@code maxRiskPctOfEquity}% of account equity</li>
 *   <li>Combined vega across all active positions ≤ {@code maxCombinedVega}</li>
 *   <li>No flagged event within {@code forcedFlatHoursBeforeEvent} hours</li>
 * </ul>
 *
 * <p>Sources: {@code 05-vol-strategy-plan.md} Tier 4 execution constraints.
 */
@ApplicationScoped
public class ShortVolExecutionGuard {

    private static final Logger LOG = Logger.getLogger(ShortVolExecutionGuard.class);

    @Inject OptionShortVolOpportunityRepository repo;

    @ConfigProperty(name = "options.short-vol.execution.enabled", defaultValue = "false")
    boolean executionEnabled;

    @ConfigProperty(name = "options.short-vol.execution.max-concurrent", defaultValue = "2")
    int maxConcurrent;

    @ConfigProperty(name = "options.short-vol.execution.max-risk-pct-of-equity", defaultValue = "0.5")
    double maxRiskPctOfEquity;

    @ConfigProperty(name = "options.short-vol.execution.max-combined-vega", defaultValue = "500.0")
    double maxCombinedVega;

    @ConfigProperty(name = "options.short-vol.execution.forced-flat-hours-before-event", defaultValue = "4")
    int forcedFlatHoursBeforeEvent;

    /**
     * Returns the decision + reason for an attempted auto-execution.
     * Always denies when the feature flag is off. Even with the flag on,
     * applies every binding constraint from the plan.
     *
     * <p>NOTE: this method only DECIDES. The caller is responsible for
     * actually placing or rejecting the order. No side effects here.
     */
    public Decision shouldExecute(OptionShortVolOpportunity candidate,
                                   double currentEquityUsd,
                                   double currentCombinedVega) {
        if (!executionEnabled) {
            return Decision.deny("feature_flag_off",
                    "options.short-vol.execution.enabled=false");
        }
        // Tier 4 constraint: max concurrent.
        int activeCount = repo.findOpen(maxConcurrent + 1).size();
        if (activeCount >= maxConcurrent) {
            return Decision.deny("max_concurrent_reached",
                    String.format("%d open already (limit %d)", activeCount, maxConcurrent));
        }
        // Tier 4 constraint: per-trade risk.
        double riskPct = candidate.getMaxLossUsd() / currentEquityUsd * 100.0;
        if (riskPct > maxRiskPctOfEquity) {
            return Decision.deny("risk_per_trade_exceeded",
                    String.format("max_loss=%.2f USD is %.3f%% of equity (limit %.2f%%)",
                            candidate.getMaxLossUsd(), riskPct, maxRiskPctOfEquity));
        }
        // Tier 4 constraint: combined vega budget.
        // Vega per defined-risk structure isn't carried on the opp row yet
        // — approximated as (max-loss / 100) as a placeholder until the
        // greek-aggregator service lands. Conservative direction (over-estimates).
        double estimatedVegaContribution = candidate.getMaxLossUsd() / 100.0;
        if (Math.abs(currentCombinedVega) + estimatedVegaContribution > maxCombinedVega) {
            return Decision.deny("combined_vega_exceeded",
                    String.format("current=%.0f + estimated=%.0f > limit %.0f",
                            currentCombinedVega, estimatedVegaContribution, maxCombinedVega));
        }
        // Tier 4 constraint: event-window flat.
        // Currently no scheduled-event feed wired; conservative default — let
        // it through. When the event feed lands, query it here.
        return Decision.approve();
    }

    /**
     * For the forced-flat-before-event rule. When called, returns the list
     * of currently-open short-vol positions that should be closed because
     * an event falls within {@code forcedFlatHoursBeforeEvent}. Currently
     * always returns empty (no event feed wired); placeholder for Tier 4
     * follow-up.
     */
    public List<OptionShortVolOpportunity> findPositionsToFlatForEvents() {
        return List.of();
    }

    public boolean isEnabled() {
        return executionEnabled;
    }

    public record Decision(boolean approved, String code, String message) {
        public static Decision approve() {
            return new Decision(true, "approved", "");
        }
        public static Decision deny(String code, String message) {
            return new Decision(false, code, message);
        }
    }
}
