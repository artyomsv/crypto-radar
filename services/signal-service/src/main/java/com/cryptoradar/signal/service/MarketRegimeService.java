package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.MarketRegime;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Classifies the current market regime (BULL / BEAR / CHOP) from BTC's
 * daily price action. Consumed by {@link SignalEngine} to modulate emission
 * thresholds so counter-trend signals face a higher bar than trend-aligned
 * ones.
 *
 * <p>Algorithm (deliberately simple):
 * <ol>
 *   <li>Fetch the last 60 daily BTC candles.</li>
 *   <li>Compute the 50-day simple moving average of closes.</li>
 *   <li>Measure current close vs. SMA and SMA slope (current SMA vs. 7d ago).</li>
 *   <li>Classify:
 *       <ul>
 *         <li>BULL if close > SMA × (1 + {@link #BAND_PCT}) AND SMA rising</li>
 *         <li>BEAR if close < SMA × (1 − {@link #BAND_PCT}) AND SMA falling</li>
 *         <li>otherwise CHOP</li>
 *       </ul></li>
 * </ol>
 *
 * <p>Refreshes every 15 minutes via the scheduler. Graceful degradation to
 * {@link MarketRegime#UNKNOWN} on fetch failure or insufficient history.
 */
@ApplicationScoped
public class MarketRegimeService {

    private static final Logger LOG = Logger.getLogger(MarketRegimeService.class);

    private static final String BTC_SYMBOL = "BTCUSDT";
    private static final String INTERVAL_1D = "1d";
    private static final int CANDLE_FETCH_LIMIT = 60;
    private static final int SMA_WINDOW = 50;
    private static final int SLOPE_LOOKBACK = 7;

    /** Close-to-SMA band width. Inside this band = no trend. */
    private static final double BAND_PCT = 0.02;

    private final CandleClient candleClient;
    private final AtomicReference<MarketRegime> currentRegime =
            new AtomicReference<>(MarketRegime.UNKNOWN);

    public MarketRegimeService(CandleClient candleClient) {
        this.candleClient = candleClient;
    }

    /**
     * Primes the regime on startup so the first signal cycle doesn't emit
     * against {@link MarketRegime#UNKNOWN}.
     */
    void onStart(@Observes StartupEvent event) {
        refresh();
    }

    /** Returns the cached regime. Always safe — returns UNKNOWN if never refreshed. */
    public MarketRegime currentRegime() {
        return currentRegime.get();
    }

    /** Scheduled refresh. 15 minutes is a deliberate balance: daily candles
     * don't change within minutes, but cycling into a new classification
     * bucket sooner than the scheduler cadence would be visible latency. */
    @Scheduled(every = "15m", delayed = "10s")
    void refresh() {
        MarketRegime newRegime = classify();
        MarketRegime previous = currentRegime.getAndSet(newRegime);
        if (previous != newRegime) {
            LOG.infof("Market regime transition: %s → %s", previous, newRegime);
        }
    }

    private MarketRegime classify() {
        List<CandleBar> bars = candleClient.fetchRecent(BTC_SYMBOL, INTERVAL_1D, CANDLE_FETCH_LIMIT);
        if (bars.size() < SMA_WINDOW) {
            LOG.warnf("Insufficient BTC 1d history for regime: got %d, need %d",
                    bars.size(), SMA_WINDOW);
            return MarketRegime.UNKNOWN;
        }
        return classifyFromBars(bars);
    }

    /**
     * Pure function: computes regime from an oldest-first list of daily bars.
     * Package-private so it's directly testable without stubbing {@link CandleClient}.
     */
    MarketRegime classifyFromBars(List<CandleBar> bars) {
        if (bars.size() < SMA_WINDOW) return MarketRegime.UNKNOWN;

        double currentSma = smaOfLastN(bars, bars.size(), SMA_WINDOW);
        double priorSma = smaOfLastN(bars, bars.size() - SLOPE_LOOKBACK, SMA_WINDOW);
        double currentClose = bars.get(bars.size() - 1).close();

        boolean aboveBand = currentClose > currentSma * (1 + BAND_PCT);
        boolean belowBand = currentClose < currentSma * (1 - BAND_PCT);
        boolean smaRising = currentSma > priorSma;
        boolean smaFalling = currentSma < priorSma;

        if (aboveBand && smaRising) return MarketRegime.BULL;
        if (belowBand && smaFalling) return MarketRegime.BEAR;
        return MarketRegime.CHOP;
    }

    /**
     * SMA of closes over the {@code window} bars ending at index {@code endExclusive}.
     * Returns 0 if the window doesn't have enough bars behind {@code endExclusive}.
     */
    private double smaOfLastN(List<CandleBar> bars, int endExclusive, int window) {
        if (endExclusive < window) return 0.0;
        double sum = 0;
        for (int i = endExclusive - window; i < endExclusive; i++) {
            sum += bars.get(i).close();
        }
        return sum / window;
    }
}
