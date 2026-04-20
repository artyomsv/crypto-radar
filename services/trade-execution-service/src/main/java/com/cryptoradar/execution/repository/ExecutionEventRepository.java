package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExecutionEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ExecutionEventRepository implements PanacheRepository<ExecutionEvent> {

    public List<ExecutionEvent> findRecentForAccount(Long accountId, int limit) {
        return find("exchangeAccountId = ?1", Sort.descending("createdAt"), accountId)
                .page(0, limit).list();
    }
}
