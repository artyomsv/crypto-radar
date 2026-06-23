package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The Phase 2 control: direction = invert(sign(overallScore)), 1:1 geometry. A
 * behavior-preserving extraction of the original inline scan logic — the running
 * v2-1to1-flip experiment must not change shape. Geometry/direction knobs stay on
 * their original property names so existing config keeps driving it.
 */
@ApplicationScoped
public class FlipGenerator implements CandidateGenerator {

    @ConfigProperty(name = "probability.config-tag", defaultValue = "v2-1to1-flip")
    String tag;
    @ConfigProperty(name = "probability.geometry.stop-atr-mult", defaultValue = "1.5")
    double stopAtrMult;
    @ConfigProperty(name = "probability.geometry.target-r", defaultValue = "1.0")
    double targetR;
    @ConfigProperty(name = "probability.direction.invert", defaultValue = "true")
    boolean invertDirection;
    @ConfigProperty(name = "probability.generator.flip.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "probability.generator.flip.run-llm", defaultValue = "true")
    boolean runLlm;

    @Override public String tag() { return tag; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean runLlm() { return runLlm; }

    @Override
    public Optional<Candidate> build(DirectionContext ctx) {
        boolean bullish = ctx.signal().getOverallScore() >= 0;
        if (invertDirection) bullish = !bullish;
        String direction = bullish ? Candidate.LONG : Candidate.SHORT;
        return Optional.of(CandidateBuilder.build(direction, ctx.entry(), ctx.atr(), stopAtrMult, targetR));
    }
}
