package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExchangeAccount;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class ExchangeAccountRepository implements PanacheRepository<ExchangeAccount> {

    public Optional<ExchangeAccount> findByExchangeAndEnvironment(String exchange, String environment) {
        return find("exchange = ?1 and environment = ?2", exchange, environment).firstResultOptional();
    }
}
