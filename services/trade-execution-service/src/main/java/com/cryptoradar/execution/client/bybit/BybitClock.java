package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ServerTimeResult;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Drift-immune clock for Bybit request signing.
 *
 * <p>Bybit rejects any signed request whose {@code X-BAPI-TIMESTAMP} falls
 * outside {@code [serverTime - recvWindow, serverTime + 1s]} with
 * {@code retCode=10002}. The Docker Desktop / WSL2 host clock drifts behind
 * real time after the laptop sleeps (observed: 34s in a real incident), which
 * exceeds any sane {@code recv_window} and silently kills every signed call —
 * wallet, positions, orders. Inflating {@code recv_window} is an arms race the
 * clock eventually wins, and it weakens replay protection.
 *
 * <p>So we never sign with the raw local clock. We periodically read Bybit's
 * own server time (unsigned {@code /v5/market/time}) and hold the offset
 * {@code serverMillis - localMillis}. {@link #nowMillis()} returns
 * {@code System.currentTimeMillis() + offset}, so the signed timestamp tracks
 * Bybit's clock no matter how far the host drifts.
 *
 * <p>Three recovery layers keep this self-healing: a startup prime, a periodic
 * resync, and an on-demand resync that {@link BybitV5RestClient} triggers when
 * it still observes a 10002 (e.g. immediately after a laptop resume, before the
 * periodic tick fires). Every sync is fail-open — a failed read keeps the prior
 * offset, which still beats the raw drifted clock.
 */
@ApplicationScoped
public class BybitClock {

    private static final Logger LOG = Logger.getLogger(BybitClock.class);

    /** Server times below 2020-01-01 are treated as garbage and ignored. */
    private static final long MIN_PLAUSIBLE_EPOCH_MS = 1_577_836_800_000L;

    /** Only log an offset change when it moves more than this — avoids noise. */
    private static final long OFFSET_LOG_THRESHOLD_MS = 1_000;

    private volatile long offsetMillis = 0L;

    @Inject
    BybitV5RestClient restClient;

    @ConfigProperty(name = "bybit.clock.sync-environment", defaultValue = "MAINNET")
    String syncEnvironment;

    void onStart(@Observes StartupEvent event) {
        sync();
    }

    @Scheduled(every = "${bybit.clock.sync-interval:30s}", delayed = "5s")
    void scheduledSync() {
        sync();
    }

    /** Host time corrected onto Bybit's clock. Use this for {@code X-BAPI-TIMESTAMP}. */
    public long nowMillis() {
        return System.currentTimeMillis() + offsetMillis;
    }

    public long offsetMillis() {
        return offsetMillis;
    }

    /**
     * Re-reads Bybit server time and updates the offset. Fail-open: on any
     * error or non-OK response the previous offset is kept and logged at WARN —
     * a stale offset still beats the raw drifted clock. Safe to call from the
     * REST retry path; the underlying call is unsigned so it cannot itself trip
     * a 10002.
     */
    public void sync() {
        try {
            BybitResponse<ServerTimeResult> resp = restClient.getServerTime(syncEnvironment);
            if (!resp.isOk()) {
                LOG.warnf("Bybit clock sync non-OK: retCode=%d retMsg=%s (keeping offset=%dms)",
                        resp.retCode(), resp.retMsg(), offsetMillis);
                return;
            }
            applyServerTime(resp.time());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Bybit clock sync failed (keeping offset=%dms)", offsetMillis);
        }
    }

    /**
     * Recomputes the offset from a Bybit server epoch-ms value. Package-private
     * so unit tests can drive it deterministically without the network.
     */
    void applyServerTime(long serverEpochMillis) {
        if (serverEpochMillis < MIN_PLAUSIBLE_EPOCH_MS) {
            LOG.warnf("Ignoring implausible Bybit server time %d (keeping offset=%dms)",
                    serverEpochMillis, offsetMillis);
            return;
        }
        long previous = offsetMillis;
        offsetMillis = serverEpochMillis - System.currentTimeMillis();
        if (Math.abs(offsetMillis - previous) > OFFSET_LOG_THRESHOLD_MS) {
            LOG.infof("Bybit clock offset updated: %dms -> %dms", previous, offsetMillis);
        }
    }
}
