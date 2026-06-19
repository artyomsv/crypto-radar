package com.cryptoradar.signal.probability;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Panache repository for shadow probability candidates. Query surface kept to
 * what the scanner, shadow evaluator, and calibration report actually use.
 */
@ApplicationScoped
public class ProbabilityCandidateRepository implements PanacheRepository<ProbabilityCandidate> {

    /** Open candidates awaiting forward evaluation, oldest first. */
    public List<ProbabilityCandidate> findPending() {
        return list("status", Sort.by("scannedAt"), ProbabilityCandidate.STATUS_PENDING);
    }

    /** Closed candidates that carry a probability, for the calibration report. */
    public List<ProbabilityCandidate> findClosedWithStatsProb() {
        return list("status <> ?1 and statsProb is not null", ProbabilityCandidate.STATUS_PENDING);
    }

    /** Closed candidates for one config tag — calibration is scoped per config. */
    public List<ProbabilityCandidate> findClosedForTag(String configTag) {
        return list("status <> ?1 and configTag = ?2", ProbabilityCandidate.STATUS_PENDING, configTag);
    }

    /** Closed candidates with an LLM probability for one config tag (calibrator training). */
    public List<ProbabilityCandidate> findClosedWithLlmProbForTag(String configTag) {
        return list("status <> ?1 and configTag = ?2 and llmProb is not null",
                ProbabilityCandidate.STATUS_PENDING, configTag);
    }
}
