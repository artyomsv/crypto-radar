package com.cryptoradar.signal.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * JPA entity for the {@code signal_config_versions} table.
 *
 * <p>Each row is immutable after insert. "Rollback" is performed by flipping
 * {@code is_active} — no row is ever updated or deleted (except the active flag).
 *
 * <p>The {@code config} column is stored as JSONB and mapped via
 * {@link SignalConfigConverter}.
 */
@Entity
@Table(name = "signal_config_versions")
public class SignalConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version", nullable = false, unique = true)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private SignalConfig config;

    @Column(name = "description", nullable = false)
    private String description = "";

    @Column(name = "parent_version_id")
    private Long parentVersionId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy = "system";

    public SignalConfigVersion() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public SignalConfig getConfig() { return config; }
    public void setConfig(SignalConfig config) { this.config = config; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getParentVersionId() { return parentVersionId; }
    public void setParentVersionId(Long parentVersionId) { this.parentVersionId = parentVersionId; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
