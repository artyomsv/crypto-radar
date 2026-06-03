package com.cryptoradar.options.repository;

import com.cryptoradar.options.model.OptionSnapshot;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Snapshots are append-only into a TimescaleDB hypertable. Writes go through
 * raw JDBC (AgroalDataSource) for batch performance; reads use JPQL via
 * EntityManager. Mirrors the {@code MarketDataService.upsertCandlesBatch}
 * fix from this session — never use {@code EntityManager.unwrap(Connection.class)}
 * because Hibernate 6 doesn't support it.
 */
@ApplicationScoped
public class OptionSnapshotRepository {

    private static final Logger LOG = Logger.getLogger(OptionSnapshotRepository.class);

    private static final String INSERT_SQL = """
        INSERT INTO option_snapshots
            (time, underlying, symbol, expiry, strike, option_type,
             bid, ask, mark, implied_vol, delta, gamma, theta, vega,
             open_interest, volume_24h, underlying_px)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    @Inject AgroalDataSource dataSource;
    @Inject EntityManager entityManager;

    public void insertBatch(List<OptionSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            for (OptionSnapshot s : snapshots) {
                stmt.setTimestamp(1, Timestamp.from(s.getTime()));
                stmt.setString(2, s.getUnderlying());
                stmt.setString(3, s.getSymbol());
                stmt.setObject(4, s.getExpiry());
                stmt.setDouble(5, s.getStrike());
                stmt.setString(6, s.getOptionType());
                setNullableDouble(stmt, 7, s.getBid());
                setNullableDouble(stmt, 8, s.getAsk());
                setNullableDouble(stmt, 9, s.getMark());
                setNullableDouble(stmt, 10, s.getImpliedVol());
                setNullableDouble(stmt, 11, s.getDelta());
                setNullableDouble(stmt, 12, s.getGamma());
                setNullableDouble(stmt, 13, s.getTheta());
                setNullableDouble(stmt, 14, s.getVega());
                setNullableDouble(stmt, 15, s.getOpenInterest());
                setNullableDouble(stmt, 16, s.getVolume24h());
                setNullableDouble(stmt, 17, s.getUnderlyingPx());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (Exception e) {
            LOG.errorf(e, "option_snapshots batch insert failed (size=%d)", snapshots.size());
        }
    }

    /**
     * Latest snapshot per contract symbol for a given underlying. Uses
     * the (underlying, expiry, time DESC) index for chain reads.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<OptionSnapshot> latestChain(String underlying) {
        return entityManager.createNativeQuery("""
            SELECT DISTINCT ON (symbol) *
            FROM option_snapshots
            WHERE underlying = :underlying
              AND time > now() - interval '5 minutes'
            ORDER BY symbol, time DESC
            """, OptionSnapshot.class)
                .setParameter("underlying", underlying)
                .getResultList();
    }

    /**
     * Most recent snapshot for a single contract. Used by the enricher to
     * attach live Greeks to an open opportunity's two legs. Hits the
     * {@code (symbol, time DESC)} index — one row, sub-millisecond.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public Optional<OptionSnapshot> latestForSymbol(String symbol) {
        List<OptionSnapshot> rows = entityManager.createNativeQuery("""
            SELECT * FROM option_snapshots
            WHERE symbol = :symbol
              AND time > now() - interval '15 minutes'
            ORDER BY time DESC
            LIMIT 1
            """, OptionSnapshot.class)
                .setParameter("symbol", symbol)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void setNullableDouble(PreparedStatement stmt, int idx, Double v) throws java.sql.SQLException {
        if (v == null) stmt.setNull(idx, Types.DOUBLE);
        else stmt.setDouble(idx, v);
    }
}
