package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Same entries as {@code v3-feature-dir} (delegates to {@link FeatureDirectionGenerator});
 * differs from v4 only in the exit config. Where v4 uses the DEFAULT trail
 * (activate 1.0R, lock 0.5R), v5 uses an EARLY-activation trail (activate 0.5R,
 * lock ~0.2R — configured in {@code ShadowOutcomeEvaluator.trailConfigFor}).
 *
 * <p>Rationale: the v4 experiment showed trailing loses because most winners
 * tag ~0.5–1.5R and reverse. A backtest suggested activating at 0.5R and banking
 * a small lock rescues that fat population (win rate 56%→71%, EV ~2–3x). But the
 * same backtest overstated v4 by ~0.28R vs its live result, and a 0.2R lock is
 * slippage-sensitive — so this runs as a live shadow to confirm, not on faith.
 * v4 stays running alongside for the historical comparison.
 */
@ApplicationScoped
public class FeatureDirectionEarlyTrailGenerator implements CandidateGenerator {

    @Inject FeatureDirectionGenerator featureDir;

    @ConfigProperty(name = "probability.generator.feature-dir-early.tag", defaultValue = "v5-feature-dir-early")
    String tag;
    @ConfigProperty(name = "probability.generator.feature-dir-early.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "probability.generator.feature-dir-early.run-llm", defaultValue = "false")
    boolean runLlm;

    @Override public String tag() { return tag; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean runLlm() { return runLlm; }

    @Override
    public Optional<Candidate> build(DirectionContext ctx) {
        // Identical entry/direction/geometry as v3 — only the exit differs, applied
        // by ShadowOutcomeEvaluator for this tag (early-activation trail).
        return featureDir.build(ctx);
    }
}
