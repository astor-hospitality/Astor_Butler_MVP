package museon_online.astor_butler.integration.saby;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** One row per HTTP call to Saby. Rows carry no bodies, tokens, ids or guest data, so they are safe to keep and read. */
@Repository
@RequiredArgsConstructor
public class SabyCallLogRepository {

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_HTTP_ERROR = "HTTP_ERROR";
    public static final String OUTCOME_IO_ERROR = "IO_ERROR";

    private final JdbcTemplate jdbcTemplate;

    /** The unified call log (docs/operations/CALL_LOG.md): optional so the constructor and its tests stay as they are. */
    private museon_online.astor_butler.domain.calllog.CallLogWriter callLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCallLog(museon_online.astor_butler.domain.calllog.CallLogWriter callLog) {
        this.callLog = callLog;
    }

    /** How one operation has been doing: how often it was called, how often it failed and how long it took. */
    public record OperationSummary(String httpMethod, String operation, long calls, long failures, long averageMs, long maxMs) {
    }

    public void record(String httpMethod, String operation, int httpStatus, String outcome, long durationMs) {
        if (callLog != null && callLog.enabled()) {
            callLog.tool("saby:" + operation, outcome, httpStatus >= 400 ? String.valueOf(httpStatus) : null, durationMs);
        }
        jdbcTemplate.update("""
                INSERT INTO saby_call_log (http_method, operation, http_status, outcome, duration_ms, occurred_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                httpMethod,
                operation,
                httpStatus,
                outcome,
                Math.max(0, durationMs)
        );
    }

    /** Operations called since the given moment, the ones that fail most first. */
    public List<OperationSummary> summarySince(Instant since) {
        return jdbcTemplate.query("""
                SELECT http_method,
                       operation,
                       COUNT(*) AS calls,
                       SUM(CASE WHEN outcome <> 'OK' THEN 1 ELSE 0 END) AS failures,
                       AVG(duration_ms) AS average_ms,
                       MAX(duration_ms) AS max_ms
                FROM saby_call_log
                WHERE occurred_at >= ?
                GROUP BY http_method, operation
                ORDER BY failures DESC, calls DESC, operation
                """,
                (rs, rowNum) -> new OperationSummary(
                        rs.getString("http_method"),
                        rs.getString("operation"),
                        rs.getLong("calls"),
                        rs.getLong("failures"),
                        Math.round(rs.getDouble("average_ms")),
                        rs.getLong("max_ms")
                ),
                Timestamp.from(since)
        );
    }
}
