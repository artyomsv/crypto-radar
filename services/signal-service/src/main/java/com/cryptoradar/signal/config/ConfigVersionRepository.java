package com.cryptoradar.signal.config;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Panache repository for {@link SignalConfigVersion}.
 *
 * <p>All queries are narrowly scoped to what {@link ConfigService} actually
 * needs — keeps the surface small and indexable.
 */
@ApplicationScoped
public class ConfigVersionRepository implements PanacheRepository<SignalConfigVersion> {

    /**
     * Returns the single active version, or empty if the table has no active row.
     * Uses the partial unique index {@code idx_signal_config_one_active} implicitly.
     */
    public Optional<SignalConfigVersion> findActive() {
        return find("isActive = true").firstResultOptional();
    }

    /**
     * Finds a version by its surrogate key.
     */
    public Optional<SignalConfigVersion> findById(long id) {
        return findByIdOptional(id);
    }

    /**
     * Lists versions newest-first for the admin UI. Caller controls pagination.
     *
     * @param limit  page size (caller must clamp to MAX_LIST_LIMIT)
     * @param offset row offset for pagination
     */
    public List<SignalConfigVersion> listVersions(int limit, int offset) {
        return find("order by createdAt desc")
                .page(offset / Math.max(limit, 1), Math.max(limit, 1))
                .list();
    }

    /**
     * Returns the next version number (max existing + 1, or 1 if the table is empty).
     * The caller wraps this in a transaction to avoid a TOCTOU gap.
     */
    public int nextVersionNumber() {
        Number max = (Number) getEntityManager()
                .createQuery("SELECT MAX(v.version) FROM SignalConfigVersion v")
                .getSingleResult();
        return (max == null) ? 1 : max.intValue() + 1;
    }
}
