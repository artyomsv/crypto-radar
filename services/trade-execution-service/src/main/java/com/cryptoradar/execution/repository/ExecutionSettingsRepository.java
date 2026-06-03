package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExecutionSettings;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class ExecutionSettingsRepository implements PanacheRepository<ExecutionSettings> {

    public Optional<ExecutionSettings> findSingleton() {
        return findByIdOptional(1L);
    }
}
