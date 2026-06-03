package com.cryptoradar.signal.service;

import com.cryptoradar.signal.config.ConfigService;
import com.cryptoradar.signal.config.SignalConfig;

/**
 * Minimal ConfigService stub for unit tests. Returns whatever config was
 * supplied at construction time — no DB, no scheduler, no CDI context.
 *
 * <p>Usage:
 * <pre>
 *   SignalEngine engine = new SignalEngine(new FakeConfigService(SignalConfig.defaults()));
 * </pre>
 */
class FakeConfigService extends ConfigService {

    private final SignalConfig config;

    FakeConfigService(SignalConfig config) {
        // repository + backtestService are unused — we override getActive() directly.
        super(null, null);
        this.config = config;
    }

    @Override
    public SignalConfig getActive() {
        return config;
    }
}
