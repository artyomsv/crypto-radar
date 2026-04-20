package com.cryptoradar.signal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Timestamped marker for a signal-engine change that altered outcome shape
 * or behavior. Surfaces to the frontend as a list of cutover points so UI
 * can render deploy lines on charts and metric filters can key off them.
 *
 * <p>Populated at schema-init time (see {@code db/init/signal-init.sql}).
 * Append-only — never updated or deleted once inserted.
 */
@Entity
@Table(name = "deployment_markers")
public class DeploymentMarker {

    @Id
    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public DeploymentMarker() {
    }

    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant deployedAt) { this.deployedAt = deployedAt; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
