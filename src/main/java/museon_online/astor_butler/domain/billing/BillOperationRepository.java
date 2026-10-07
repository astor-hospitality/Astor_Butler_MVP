package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/** The journal of bill operations. It only adds and reads: there is deliberately no update or delete here. */
@Repository
@RequiredArgsConstructor
public class BillOperationRepository {

    private static final int MAX_DETAILS = 500;

    private final JdbcTemplate jdbcTemplate;

    public void append(Long billId, BillOperationType type, boolean ok, BillActor actor, String errorCode, String details) {
        jdbcTemplate.update("""
                INSERT INTO bill_operations (bill_id, type, ok, actor, error_code, details, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                billId,
                type.name(),
                ok,
                (actor == null ? BillActor.SYSTEM : actor).name(),
                trim(errorCode, 80),
                trim(details, MAX_DETAILS)
        );
    }

    public List<BillOperation> findByBill(Long billId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM bill_operations
                WHERE bill_id = ?
                ORDER BY id
                """,
                mapper(),
                billId
        );
    }

    private RowMapper<BillOperation> mapper() {
        return (rs, rowNum) -> {
            Timestamp occurredAt = rs.getTimestamp("occurred_at");
            return new BillOperation(
                    rs.getLong("id"),
                    rs.getLong("bill_id"),
                    BillOperationType.valueOf(rs.getString("type")),
                    rs.getBoolean("ok"),
                    BillActor.valueOf(rs.getString("actor")),
                    rs.getString("error_code"),
                    rs.getString("details"),
                    occurredAt == null ? null : occurredAt.toInstant()
            );
        };
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
