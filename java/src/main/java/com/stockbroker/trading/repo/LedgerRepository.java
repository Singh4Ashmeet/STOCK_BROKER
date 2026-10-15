package com.stockbroker.trading.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ledger repository for querying ledger entries using NamedParameterJdbcTemplate.
 */
@Repository
public class LedgerRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LedgerRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Object[]> findByUserId(UUID userId, int limit) {
        String sql = """
            SELECT le.id, le.asset, le.amount, lt.kind as type, lt.ref_type, lt.ref_id, le.created_at
            FROM ledger_entries le
            JOIN ledger_transactions lt ON le.transaction_id = lt.id
            WHERE lt.user_id = :userId
            ORDER BY le.created_at DESC
            LIMIT :limit
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", limit);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new Object[] {
                rs.getObject("id"),
                rs.getString("asset"),
                rs.getBigDecimal("amount"),
                rs.getString("type"),
                rs.getString("ref_type"),
                rs.getObject("ref_id"),
                rs.getTimestamp("created_at").toInstant()
        });
    }

    public List<Object[]> findByUserIdAndTimeRange(UUID userId, Instant from, Instant to, int limit) {
        String sql = """
            SELECT le.id, le.asset, le.amount, lt.kind as type, lt.ref_type, lt.ref_id, le.created_at
            FROM ledger_entries le
            JOIN ledger_transactions lt ON le.transaction_id = lt.id
            WHERE lt.user_id = :userId
              AND le.created_at >= :from
              AND le.created_at <= :to
            ORDER BY le.created_at DESC
            LIMIT :limit
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", limit);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new Object[] {
                rs.getObject("id"),
                rs.getString("asset"),
                rs.getBigDecimal("amount"),
                rs.getString("type"),
                rs.getString("ref_type"),
                rs.getObject("ref_id"),
                rs.getTimestamp("created_at").toInstant()
        });
    }
}