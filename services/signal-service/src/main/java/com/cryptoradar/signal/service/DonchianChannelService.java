package com.cryptoradar.signal.service;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches per-symbol daily Donchian snapshots. Mirrors
 * {@code MarketRegimeService}'s 1d-candle approach but is lazy + per-symbol:
 * {@link #snapshotFor(String)} recomputes when the cached value is older than
 * {@link #TTL_MILLIS}. Fail-safe — returns {@link Optional#empty()} when
 * history is short or the fetch fails, so detectors simply no-op for that
 * symbol rather than throwing.
 */
@ApplicationScoped
public class DonchianChannelService {

    private static final Logger LOG = Logger.getLogger(DonchianChannelService.class);

    private static final String INTERVAL_1D = "1d";
    private static final int FETCH_LIMIT = 60;
    private static final int N_PERIOD = 20;
    private static final int LOOKBACK_55 = 55;
    private static final int LOOKBACK_20 = 20;
    private static final int LOOKBACK_10 = 10;
    /** 56 bars = 55 completed + 1 excluded "today" bar. */
    private static final int MIN_BARS = LOOKBACK_55 + 1;
    private static final long TTL_MILLIS = 3_600_000L; // 1h — daily candles change once a day
    private static final String S1_STRATEGY = "turtle-s1";

    private final CandleClient candleClient;
    private final SignalOutcomeRepository outcomeRepo;
    private final ConcurrentHashMap<String, DonchianSnapshot> cache = new ConcurrentHashMap<>();

    public DonchianChannelService(CandleClient candleClient, SignalOutcomeRepository outcomeRepo) {
        this.candleClient = candleClient;
        this.outcomeRepo = outcomeRepo;
    }

    /** Cached snapshot for the symbol, recomputed when stale. Empty on failure. */
    public Optional<DonchianSnapshot> snapshotFor(String symbol) {
        DonchianSnapshot cached = cache.get(symbol);
        if (cached != null && !isStale(cached)) {
            return Optional.of(cached);
        }
        try {
            List<CandleBar> bars = candleClient.fetchRecent(symbol, INTERVAL_1D, FETCH_LIMIT);
            if (bars == null || bars.size() < MIN_BARS) {
                LOG.warnf("Donchian: insufficient 1d history for %s (got %d, need %d)",
                        symbol, bars == null ? 0 : bars.size(), MIN_BARS);
                return Optional.empty();
            }
            DonchianSnapshot snap = buildSnapshot(bars,
                    lastS1Winner(symbol, "LONG"),
                    lastS1Winner(symbol, "SHORT"));
            cache.put(symbol, snap);
            return Optional.of(snap);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Donchian: snapshot build failed for %s — skipping", symbol);
            return Optional.empty();
        }
    }

    private boolean isStale(DonchianSnapshot snap) {
        return Instant.now().toEpochMilli() - snap.computedAt().toEpochMilli() > TTL_MILLIS;
    }

    private boolean lastS1Winner(String symbol, String direction) {
        return outcomeRepo.findLastClosedByStrategy(symbol, S1_STRATEGY, direction)
                .map(SignalOutcome::getRealizedRMultiple)
                .map(r -> r != null && r > 0)
                .orElse(false);
    }

    /**
     * Pure snapshot builder. {@code bars} is oldest-first; the LAST bar is the
     * current/forming day and is excluded from all channels and from N (a
     * breakout is "price exceeds the prior n COMPLETED bars"; N is an ATR over
     * completed bars for the same reason). Package-private for testing.
     */
    DonchianSnapshot buildSnapshot(List<CandleBar> bars, boolean lastS1LongWinner, boolean lastS1ShortWinner) {
        int size = bars.size();
        if (size < MIN_BARS) {
            throw new IllegalArgumentException("Donchian needs >= " + MIN_BARS
                    + " daily bars, got " + size);
        }
        double[] highs = new double[size];
        double[] lows = new double[size];
        double[] closes = new double[size];
        for (int i = 0; i < size; i++) {
            highs[i] = bars.get(i).high();
            lows[i] = bars.get(i).low();
            closes[i] = bars.get(i).close();
        }
        int endExclusive = size - 1; // exclude today's forming bar
        double[] nHighs = java.util.Arrays.copyOf(highs, endExclusive);
        double[] nLows = java.util.Arrays.copyOf(lows, endExclusive);
        double[] nCloses = java.util.Arrays.copyOf(closes, endExclusive);
        return new DonchianSnapshot(
                DonchianMath.channelHigh(highs, endExclusive, LOOKBACK_20),
                DonchianMath.channelLow(lows, endExclusive, LOOKBACK_20),
                DonchianMath.channelHigh(highs, endExclusive, LOOKBACK_10),
                DonchianMath.channelLow(lows, endExclusive, LOOKBACK_10),
                DonchianMath.channelHigh(highs, endExclusive, LOOKBACK_55),
                DonchianMath.channelLow(lows, endExclusive, LOOKBACK_55),
                DonchianMath.computeN(nHighs, nLows, nCloses, N_PERIOD),
                lastS1LongWinner,
                lastS1ShortWinner,
                Instant.now());
    }
}
