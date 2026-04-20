package com.cryptoradar.signal.repository;

import com.cryptoradar.signal.model.DeploymentMarker;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

/**
 * Panache repository for {@link DeploymentMarker}.
 *
 * <p>Deliberately tiny — deployment markers are read-only from the app's
 * perspective (only the init script inserts rows). The service exposes
 * the full list chronologically for UI consumption.
 */
@ApplicationScoped
public class DeploymentMarkerRepository implements PanacheRepositoryBase<DeploymentMarker, Instant> {

    /** All markers, oldest-first — the natural order for rendering a timeline. */
    public List<DeploymentMarker> findAllOrdered() {
        return find("order by deployedAt asc").list();
    }
}
