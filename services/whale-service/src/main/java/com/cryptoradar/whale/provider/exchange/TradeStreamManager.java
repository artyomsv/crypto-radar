package com.cryptoradar.whale.provider.exchange;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class TradeStreamManager {

    private static final Logger LOG = Logger.getLogger(TradeStreamManager.class);

    @Inject
    Instance<ExchangeTradeStreamProvider> providers;

    void onStart(@Observes StartupEvent event) {
        // Stagger connections to avoid overwhelming all exchanges at once
        int delay = 5;
        for (ExchangeTradeStreamProvider provider : providers) {
            int finalDelay = delay;
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                LOG.infof("Connecting to %s trade stream...", provider.getExchangeName());
                try {
                    provider.connect();
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to connect to %s", provider.getExchangeName());
                }
            }, finalDelay, TimeUnit.SECONDS);
            delay += 3; // 3s gap between each exchange connection
        }
    }

    public List<Map<String, Object>> getProviderStatus() {
        List<Map<String, Object>> status = new ArrayList<>();
        for (ExchangeTradeStreamProvider provider : providers) {
            status.add(Map.of(
                    "name", provider.getExchangeName(),
                    "type", "websocket",
                    "status", provider.isConnected() ? "connected" : "disconnected"
            ));
        }
        return status;
    }
}
