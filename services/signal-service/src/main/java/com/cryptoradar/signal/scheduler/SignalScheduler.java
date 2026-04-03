package com.cryptoradar.signal.scheduler;

import com.cryptoradar.signal.event.RedisEventPublisher;
import com.cryptoradar.signal.model.SignalOverview;
import com.cryptoradar.signal.service.SignalService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SignalScheduler {

    private static final Logger LOG = Logger.getLogger(SignalScheduler.class);

    private final SignalService signalService;
    private final RedisEventPublisher redisPublisher;

    public SignalScheduler(SignalService signalService, RedisEventPublisher redisPublisher) {
        this.signalService = signalService;
        this.redisPublisher = redisPublisher;
    }

    void onStart(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            LOG.info("Computing initial trading signals (15s after startup)...");
            computeAndPublish();
        }, 15, TimeUnit.SECONDS);
    }

    @Scheduled(every = "${scheduler.signal.interval}", identity = "signal-periodic")
    void recomputeSignals() {
        LOG.info("Scheduled signal recomputation starting");
        computeAndPublish();
    }

    private void computeAndPublish() {
        try {
            SignalOverview overview = signalService.computeAllSignals();
            redisPublisher.publishOverview(overview);
            LOG.infof("Signal computation complete — %d signals (bias=%s, top=%s)",
                    overview.getSignals().size(),
                    overview.getMarketBias(),
                    overview.getTopOpportunity() != null
                            ? overview.getTopOpportunity().getSymbol() + ":" + overview.getTopOpportunity().getSignal()
                            : "none");
        } catch (Exception e) {
            LOG.errorf(e, "Failed to compute/publish trading signals");
        }
    }
}
