package museon_online.astor_butler.domain.calllog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * The {@code call_log} table. Every call runs in its own transaction, so a row is written even when the caller's
 * transaction rolls back, and a failed write never marks the caller's transaction rollback-only.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CallLogRepository {

    static final String CSV_HEADER =
            "ts,source,pseudo_user_id,correlation_id,call_type,name,result,error_code,tokens_in,tokens_out,cost_rub,latency_ms";

    private final JdbcTemplate jdbcTemplate;

    public CallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(CallLogEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO call_log (ts, source, pseudo_user_id, correlation_id, call_type, name, result, error_code,
                                      tokens_in, tokens_out, cost_rub, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Timestamp.from(entry.ts() == null ? Instant.now() : entry.ts()),
                cut(entry.source(), 32),
                cut(entry.pseudoUserId(), 64),
                cut(entry.correlationId(), 64),
                cut(entry.callType(), 24),
                cut(entry.name(), 80),
                cut(entry.result(), 24),
                cut(entry.errorCode(), 64),
                entry.tokensIn(),
                entry.tokensOut(),
                entry.costRub(),
                entry.latencyMs()
        );
    }

    /** Rows with {@code from <= ts < to}, oldest first, as CSV with a header line; ts in ISO-8601 UTC. */
    public String exportCsv(Instant from, Instant to) {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        jdbcTemplate.query("""
                SELECT ts, source, pseudo_user_id, correlation_id, call_type, name, result, error_code,
                       tokens_in, tokens_out, cost_rub, latency_ms
                FROM call_log
                WHERE ts >= ? AND ts < ?
                ORDER BY ts, id
                """,
                rs -> {
                    Timestamp ts = rs.getTimestamp("ts");
                    csv.append(ts == null ? "" : ts.toInstant().toString()).append(',')
                            .append(field(rs.getString("source"))).append(',')
                            .append(field(rs.getString("pseudo_user_id"))).append(',')
                            .append(field(rs.getString("correlation_id"))).append(',')
                            .append(field(rs.getString("call_type"))).append(',')
                            .append(field(rs.getString("name"))).append(',')
                            .append(field(rs.getString("result"))).append(',')
                            .append(field(rs.getString("error_code"))).append(',')
                            .append(number(rs, "tokens_in")).append(',')
                            .append(number(rs, "tokens_out")).append(',')
                            .append(cost(rs.getBigDecimal("cost_rub"))).append(',')
                            .append(number(rs, "latency_ms")).append('\n');
                },
                Timestamp.from(from),
                Timestamp.from(to)
        );
        return csv.toString();
    }

    /** Totals over {@code from <= ts < to}. */
    public CallLogSummary summary(Instant from, Instant to) {
        return jdbcTemplate.queryForObject("""
                SELECT SUM(CASE WHEN call_type = 'llm' OR call_type LIKE 'llm:%' THEN 1 ELSE 0 END) AS llm_calls,
                       SUM(CASE WHEN call_type = 'tool' THEN 1 ELSE 0 END) AS tool_calls,
                       SUM(CASE WHEN call_type = 'guest_message' THEN 1 ELSE 0 END) AS guest_messages,
                       SUM(CASE WHEN call_type = 'target_action' THEN 1 ELSE 0 END) AS target_actions,
                       COUNT(DISTINCT pseudo_user_id) AS distinct_users,
                       COALESCE(SUM(tokens_in), 0) AS tokens_in,
                       COALESCE(SUM(tokens_out), 0) AS tokens_out,
                       COALESCE(SUM(cost_rub), 0) AS cost_rub
                FROM call_log
                WHERE ts >= ? AND ts < ?
                """,
                (rs, rowNum) -> new CallLogSummary(
                        rs.getLong("llm_calls"),
                        rs.getLong("tool_calls"),
                        rs.getLong("guest_messages"),
                        rs.getLong("target_actions"),
                        rs.getLong("distinct_users"),
                        rs.getLong("tokens_in"),
                        rs.getLong("tokens_out"),
                        scale(rs.getBigDecimal("cost_rub"))
                ),
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? "" : Long.toString(value);
    }

    private static String cost(BigDecimal value) {
        return value == null ? "" : scale(value).toPlainString();
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** RFC 4180 quoting; a leading formula character is neutralized so a spreadsheet does not evaluate the cell. */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }
}
