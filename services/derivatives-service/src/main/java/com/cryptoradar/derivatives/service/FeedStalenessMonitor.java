package com.cryptoradar.derivatives.service;

import io.agroal.api.AgroalDataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Watches the freshness of the market-data feed tables and raises a visible
 * alarm when one stops advancing.
 *
 * <p>Motivation: three feeds (liquidations, long_short_ratio,
 * option_historical_vol) were each dead for weeks while their collectors logged
 * "connected" / "refreshed N symbols" — the failures were invisible because
 * nothing watched whether rows were actually landing. This monitor turns that
 * silent death into a WARN the moment a feed's newest row is older than its
 * per-feed threshold.
 *
 * <p>All monitored tables live in the shared TimescaleDB ({@code marketdata})
 * and key on a {@code time} column. News (separate database) is out of scope.
 * Thresholds are per-feed because cadences differ wildly: funding refreshes
 * every 10s, liquidations are event-driven and can be legitimately quiet for a
 * while — so event-driven feeds get generous windows to avoid false alarms.
 */
@ApplicationScoped
public class FeedStalenessMonitor {

    private static final Logger LOG = Logger.getLogger(FeedStalenessMonitor.class);
    // Table names come from operator config, never user input; still validated
    // against this allow-pattern before interpolation as defense-in-depth.
    private static final Pattern SAFE_TABLE = Pattern.compile("[a-z_]+");
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "monitor.staleness.feeds")
    String feedsCsv;

    /** One feed's configured staleness budget. */
    private record FeedSpec(String table, int thresholdMinutes) {}

    /** A feed's current freshness, returned by the health endpoint and the alarm. */
    public record FeedFreshness(String table, Instant lastRow, Long ageMinutes,
                                int thresholdMinutes, boolean stale) {}

    @Scheduled(every = "{monitor.staleness.interval}", delayed = "60s", identity = "feed-staleness")
    void check() {
        for (FeedFreshness f : report()) {
            if (f.stale()) {
                LOG.warnf("feed_stale table=%s last_row=%s age_min=%s threshold_min=%d",
                        f.table(), f.lastRow(), f.ageMinutes(), f.thresholdMinutes());
            }
        }
    }

    /** Current freshness for every configured feed. Never throws — fail-open per feed. */
    public List<FeedFreshness> report() {
        List<FeedFreshness> out = new ArrayList<>();
        for (FeedSpec spec : specs()) {
            out.add(freshnessOf(spec));
        }
        return out;
    }

    private FeedFreshness freshnessOf(FeedSpec spec) {
        if (!SAFE_TABLE.matcher(spec.table()).matches()) {
            LOG.warnf("Skipping feed with unsafe table name: %s", spec.table());
            return new FeedFreshness(spec.table(), null, null, spec.thresholdMinutes(), false);
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT max(time) AS latest FROM " + spec.table())) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery()) {
                Timestamp latest = rs.next() ? rs.getTimestamp("latest") : null;
                return toFreshness(spec, latest);
            }
        } catch (Exception e) {
            // Fail-open: a monitor query error must not itself raise a false feed alarm.
            LOG.debugf("Staleness check failed for %s: %s", spec.table(), e.getMessage());
            return new FeedFreshness(spec.table(), null, null, spec.thresholdMinutes(), false);
        }
    }

    private FeedFreshness toFreshness(FeedSpec spec, Timestamp latest) {
        if (latest == null) {
            // An empty feed table is stale by definition — it should have data.
            return new FeedFreshness(spec.table(), null, null, spec.thresholdMinutes(), true);
        }
        Instant lastRow = latest.toInstant();
        long ageMinutes = Duration.between(lastRow, Instant.now()).toMinutes();
        boolean stale = ageMinutes > spec.thresholdMinutes();
        return new FeedFreshness(spec.table(), lastRow, ageMinutes, spec.thresholdMinutes(), stale);
    }

    private List<FeedSpec> specs() {
        List<FeedSpec> specs = new ArrayList<>();
        for (String entry : feedsCsv.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":");
            if (parts.length != 2) {
                LOG.warnf("Ignoring malformed feed config entry: %s", trimmed);
                continue;
            }
            try {
                specs.add(new FeedSpec(parts[0].trim(), Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException e) {
                LOG.warnf("Ignoring feed config entry with bad threshold: %s", trimmed);
            }
        }
        return specs;
    }
}
