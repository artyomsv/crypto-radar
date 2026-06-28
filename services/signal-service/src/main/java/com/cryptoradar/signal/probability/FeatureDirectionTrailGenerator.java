package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Same entries as {@code v3-feature-dir} (delegates the direction + geometry to
 * {@link FeatureDirectionGenerator}); the ONLY difference is the exit policy. The
 * evaluator scores this tag with a trailing-stop exit ({@link TrailExitSimulator})
 * instead of a fixed 1:1 target, so its measured EV reflects letting winners run.
 *
 * <p>Mid-experiment backtest of v3's realized paths showed the direction model
 * picks winners that run ~2.7 ATR while losers fail at ~0.45 ATR, so a trailing
 * exit roughly triples EV (+0.08R → +0.21R net) at the same win rate. This tag
 * accrues a live out-of-sample track to confirm that before any promotion.
 *
 * <p>LLM scoring defaults OFF: these are the identical candidates as v3, so the
 * stats/LLM probabilities would duplicate v3's — the experiment here is geometry,
 * not a new probability. Skipping the LLM call halves Gemini cost.
 */
@ApplicationScoped
public class FeatureDirectionTrailGenerator implements CandidateGenerator {

    @Inject FeatureDirectionGenerator featureDir;

    @ConfigProperty(name = "probability.generator.feature-dir-trail.tag", defaultValue = "v4-feature-dir-trail")
    String tag;
    @ConfigProperty(name = "probability.generator.feature-dir-trail.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "probability.generator.feature-dir-trail.run-llm", defaultValue = "false")
    boolean runLlm;

    @Override public String tag() { return tag; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean runLlm() { return runLlm; }

    @Override
    public Optional<Candidate> build(DirectionContext ctx) {
        // Identical entry/direction/geometry as v3 — only the exit (trailing) differs,
        // and that is applied later by ShadowOutcomeEvaluator for this tag.
        return featureDir.build(ctx);
    }
}
