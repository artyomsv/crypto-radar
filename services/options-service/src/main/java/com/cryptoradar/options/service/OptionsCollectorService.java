package com.cryptoradar.options.service;

import com.cryptoradar.options.client.BybitOptionsClient;
import com.cryptoradar.options.client.dto.BybitResponse;
import com.cryptoradar.options.client.dto.HistoricalVolV5;
import com.cryptoradar.options.client.dto.OptionTickerV5;
import com.cryptoradar.options.model.OptionSnapshot;
import com.cryptoradar.options.repository.OptionSnapshotRepository;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches Bybit options market data and writes to {@code option_snapshots} +
 * {@code option_historical_vol}. Stateless — called by {@code OptionsScheduler}.
 *
 * <p>Filters tickers to contracts with expiry within {@code options.max-expiry-days}
 * of today (default 4 days). For short-horizon strangle/straddle entry, longer
 * expiries are pure noise and 10x the DB load.
 */
@ApplicationScoped
public class OptionsCollectorService {

    private static final Logger LOG = Logger.getLogger(OptionsCollectorService.class);

    // Bybit symbol format: BTC-30MAY25-72000-C (or -USDT suffix on newer USDT-collateralized contracts).
    // Date format: DDMMMYY with month in UPPERCASE ("MAY" not "May"). The default
    // `ofPattern("ddMMMyy", ENGLISH)` formatter is case-sensitive and expects
    // "May" — so we explicitly mark the parser case-insensitive.
    private static final DateTimeFormatter SYMBOL_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyy")
            .toFormatter(Locale.ENGLISH);

    @Inject BybitOptionsClient client;
    @Inject OptionSnapshotRepository snapshotRepo;
    @Inject AgroalDataSource dataSource;

    @ConfigProperty(name = "options.max-expiry-days", defaultValue = "4")
    int maxExpiryDays;

    public void collectTickers(String underlying) {
        BybitResponse<BybitOptionsClient.ListResult<OptionTickerV5>> resp;
        try {
            resp = client.getOptionTickers(underlying);
        } catch (RuntimeException e) {
            LOG.warnf(e, "tickers fetch failed for %s — skip this cycle", underlying);
            return;
        }
        if (!resp.isOk() || resp.result() == null || resp.result().list() == null) {
            LOG.warnf("tickers non-OK for %s: retCode=%d retMsg=%s",
                    underlying, resp.retCode(), resp.retMsg());
            return;
        }
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.plusDays(maxExpiryDays);

        List<OptionSnapshot> batch = new ArrayList<>();
        for (OptionTickerV5 t : resp.result().list()) {
            OptionSnapshot snap = toSnapshot(t, underlying, now, today, cutoff);
            if (snap != null) batch.add(snap);
        }
        if (batch.isEmpty()) {
            // Promoted from DEBUG to INFO — a silent zero-result cycle was the
            // root cause of a 7-day data freeze (techdebt entry). Including
            // received-count makes the misconfiguration obvious at a glance.
            LOG.infof("no in-window contracts for %s (received %d, max %d days)",
                    underlying, resp.result().list().size(), maxExpiryDays);
            return;
        }
        snapshotRepo.insertBatch(batch);
        LOG.infof("options stored %d contracts for %s", batch.size(), underlying);
    }

    /**
     * Parses {@code BTC-30MAY25-72000-C} into expiry, strike, type. Returns
     * null on parse failure or if expiry is outside the window.
     */
    OptionSnapshot toSnapshot(OptionTickerV5 t, String underlying, Instant now,
                               LocalDate today, LocalDate cutoff) {
        String symbol = t.symbol();
        if (symbol == null) return null;
        // Bybit options symbol formats:
        //   4-part legacy: BTC-30MAY25-72000-C
        //   5-part current: BTC-30MAY25-72000-C-USDT  (USDT-collateralized, since 2024)
        // We accept both.
        String[] parts = symbol.split("-");
        if (parts.length < 4 || parts.length > 5) return null;
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(parts[1], SYMBOL_DATE);
        } catch (Exception e) {
            return null;
        }
        if (expiry.isBefore(today) || expiry.isAfter(cutoff)) return null;

        double strike;
        try {
            strike = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        String optionType = parts[3];
        if (!"C".equals(optionType) && !"P".equals(optionType)) return null;

        OptionSnapshot s = new OptionSnapshot();
        s.setTime(now);
        s.setSymbol(symbol);
        s.setUnderlying(underlying);
        s.setExpiry(expiry);
        s.setStrike(strike);
        s.setOptionType(optionType);
        s.setBid(parseDouble(t.bid()));
        s.setAsk(parseDouble(t.ask()));
        s.setMark(parseDouble(t.mark()));
        s.setImpliedVol(parseDouble(t.markIv()));
        s.setDelta(parseDouble(t.delta()));
        s.setGamma(parseDouble(t.gamma()));
        s.setTheta(parseDouble(t.theta()));
        s.setVega(parseDouble(t.vega()));
        s.setOpenInterest(parseDouble(t.openInterest()));
        s.setVolume24h(parseDouble(t.volume24h()));
        s.setUnderlyingPx(parseDouble(t.underlyingPrice()));
        return s;
    }

    /**
     * Persist the latest HV reading per (underlying, period). Bybit returns a
     * time series — we keep only the most recent point per poll cycle.
     */
    public void collectHistoricalVol(String underlying, int periodDays) {
        BybitResponse<List<HistoricalVolV5>> resp;
        try {
            resp = client.getHistoricalVolatility(underlying, periodDays);
        } catch (RuntimeException e) {
            LOG.warnf(e, "HV fetch failed for %s/%d", underlying, periodDays);
            return;
        }
        if (!resp.isOk() || resp.result() == null || resp.result().isEmpty()) return;
        HistoricalVolV5 latest = resp.result().get(0);
        Double hv = parseDouble(latest.value());
        if (hv == null) return;
        Instant t = Instant.ofEpochMilli(Long.parseLong(latest.timeMs()));
        upsertHv(t, underlying, periodDays, hv);
    }

    private void upsertHv(Instant time, String underlying, int periodDays, double hv) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                 INSERT INTO option_historical_vol (time, underlying, period_days, hv)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT (time, underlying, period_days) DO NOTHING
                 """)) {
            stmt.setTimestamp(1, Timestamp.from(time));
            stmt.setString(2, underlying);
            stmt.setInt(3, periodDays);
            stmt.setDouble(4, hv);
            stmt.executeUpdate();
        } catch (Exception e) {
            LOG.warnf(e, "HV upsert failed for %s/%d", underlying, periodDays);
        }
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return null; }
    }
}
