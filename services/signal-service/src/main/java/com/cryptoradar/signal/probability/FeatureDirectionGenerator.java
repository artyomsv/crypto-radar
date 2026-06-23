package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Direction comes from {@link DirectionModel} over candle-derived indicators
 * rather than sign(overallScore); 1:1 geometry held identical to the flip so the
 * only changed variable is the direction source. Skips (returns empty) when the
 * indicators are unavailable or the model has not trained — an honest absence,
 * never a guessed direction.
 */
@ApplicationScoped
public class FeatureDirectionGenerator implements CandidateGenerator {

    @Inject DirectionModelTrainer trainer;

    @ConfigProperty(name = "probability.generator.feature-dir.tag", defaultValue = "v3-feature-dir")
    String tag;
    @ConfigProperty(name = "probability.generator.feature-dir.stop-atr-mult", defaultValue = "1.5")
    double stopAtrMult;
    @ConfigProperty(name = "probability.generator.feature-dir.target-r", defaultValue = "1.0")
    double targetR;
    @ConfigProperty(name = "probability.generator.feature-dir.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "probability.generator.feature-dir.run-llm", defaultValue = "true")
    boolean runLlm;

    @Override public String tag() { return tag; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean runLlm() { return runLlm; }

    @Override
    public Optional<Candidate> build(DirectionContext ctx) {
        if (ctx.indicators() == null) return Optional.empty();
        DirectionModel model = trainer.model();
        if (!model.isTrained()) return Optional.empty();
        double pLong = model.longWinProbability(DirectionModel.toVector(ctx.indicators()));
        String direction = pLong >= 0.5 ? Candidate.LONG : Candidate.SHORT;
        return Optional.of(CandidateBuilder.build(direction, ctx.entry(), ctx.atr(), stopAtrMult, targetR));
    }
}
