package com.cryptoradar.signal.probability;

import java.util.Optional;

/**
 * Produces one shadow trade candidate per symbol per scan for a single config
 * (geometry + direction policy), identified by {@link #tag()}. The scanner scores
 * and persists whatever each enabled generator builds. Adding a new experiment is
 * a new bean — no scanner change.
 */
public interface CandidateGenerator {

    String tag();

    boolean enabled();

    boolean runLlm();

    /** The candidate for this context, or empty to skip (e.g. model untrained). */
    Optional<Candidate> build(DirectionContext ctx);
}
