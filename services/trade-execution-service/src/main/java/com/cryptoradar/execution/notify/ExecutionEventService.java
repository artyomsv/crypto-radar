package com.cryptoradar.execution.notify;

import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Single seam through which every execution event is persisted. Persisting and
 * notifying used to be two separate concerns scattered across the lifecycle and
 * intake classes; routing them through {@link #record} guarantees that anything
 * recorded in {@code execution_events} also gets a chance to fan out to
 * Telegram, with one filter ({@link TelegramNotifier#maybeNotify}) rather than a
 * copy per call site.
 *
 * <p>The Telegram HTTP send is offloaded to a daemon thread by the notifier, so
 * this method's transaction commits without waiting on the network.
 */
@ApplicationScoped
public class ExecutionEventService {

    private final ExecutionEventRepository eventRepo;
    private final TelegramNotifier notifier;

    public ExecutionEventService(ExecutionEventRepository eventRepo, TelegramNotifier notifier) {
        this.eventRepo = eventRepo;
        this.notifier = notifier;
    }

    @Transactional
    public ExecutionEvent record(ExecutionEvent ev) {
        eventRepo.persist(ev);
        notifier.maybeNotify(ev);
        return ev;
    }
}
