package com.cryptoradar.options.scheduler;

import com.cryptoradar.options.service.OptionsCollectorService;
import com.cryptoradar.options.service.OpportunityScorer;
import com.cryptoradar.options.service.ShortVolOpportunityScorer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Three @Scheduled methods drive the full pipeline:
 *  - {@link #pollTickers()}      every 60s — refresh option_snapshots
 *  - {@link #pollHistoricalVol()} every 5min — Bybit's published HV
 *  - {@link #scoreOpportunities()} every 60s — write opportunities + Redis alerts
 *
 * Try/catch wrapping mirrors derivatives-service's scheduler — one bad
 * underlying must not break the loop for the others.
 */
@ApplicationScoped
public class OptionsScheduler {

    private static final Logger LOG = Logger.getLogger(OptionsScheduler.class);

    @Inject OptionsCollectorService collector;
    @Inject OpportunityScorer scorer;
    @Inject ShortVolOpportunityScorer shortVolScorer;

    @ConfigProperty(name = "options.underlyings", defaultValue = "BTC,ETH,SOL,XAUT,XRP,MNT,DOGE")
    String underlyingsCsv;

    private List<String> underlyings() {
        return Arrays.stream(underlyingsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Scheduled(every = "{scheduler.tickers.interval}", delayed = "15s", identity = "options-tickers")
    void pollTickers() {
        for (String u : underlyings()) {
            try {
                collector.collectTickers(u);
            } catch (Exception e) {
                LOG.errorf(e, "tickers poll failed for %s", u);
            }
        }
    }

    @Scheduled(every = "{scheduler.historical-vol.interval}", delayed = "30s", identity = "options-hv")
    void pollHistoricalVol() {
        for (String u : underlyings()) {
            for (int period : new int[]{7, 14, 30}) {
                try {
                    collector.collectHistoricalVol(u, period);
                } catch (Exception e) {
                    LOG.errorf(e, "HV poll failed for %s/%d", u, period);
                }
            }
        }
    }

    @Scheduled(every = "{scheduler.opportunity-scan.interval}", delayed = "45s", identity = "options-scoring")
    void scoreOpportunities() {
        try {
            scorer.scoreAllUnderlyings(underlyings());
        } catch (Exception e) {
            LOG.errorf(e, "opportunity scoring run failed");
        }
    }

    @Scheduled(every = "{scheduler.short-vol-scan.interval}", delayed = "50s", identity = "options-short-vol-scoring")
    void scoreShortVolOpportunities() {
        try {
            shortVolScorer.scoreAllUnderlyings(underlyings());
        } catch (Exception e) {
            LOG.errorf(e, "short-vol scoring run failed");
        }
    }
}
