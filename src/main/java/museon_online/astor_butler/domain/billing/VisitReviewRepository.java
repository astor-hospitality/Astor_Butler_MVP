package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class VisitReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public VisitReview open(Long billId, Long chatId) {
        jdbcTemplate.update("""
                INSERT INTO visit_reviews (bill_id, chat_id, prompted_at, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                billId,
                chatId
        );
        return findByBill(billId).orElseThrow();
    }

    public Optional<VisitReview> findByBill(Long billId) {
        List<VisitReview> result = jdbcTemplate.query("""
                SELECT *
                FROM visit_reviews
                WHERE bill_id = ?
                """,
                mapper(),
                billId
        );
        return result.stream().findFirst();
    }

    public List<VisitReview> findByChatId(Long chatId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM visit_reviews
                WHERE chat_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                mapper(),
                chatId,
                limit
        );
    }

    public VisitReview rate(Long billId, int stars) {
        jdbcTemplate.update("""
                UPDATE visit_reviews
                SET stars = ?,
                    rated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE bill_id = ?
                """,
                stars,
                billId
        );
        return findByBill(billId).orElseThrow();
    }

    public VisitReview thumb(Long billId, VisitReview.Thumb thumb) {
        jdbcTemplate.update("""
                UPDATE visit_reviews
                SET thumb = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE bill_id = ?
                """,
                thumb.name(),
                billId
        );
        return findByBill(billId).orElseThrow();
    }

    public VisitReview tipRequested(Long billId) {
        jdbcTemplate.update("""
                UPDATE visit_reviews
                SET tip_requested_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE bill_id = ?
                """,
                billId
        );
        return findByBill(billId).orElseThrow();
    }

    private RowMapper<VisitReview> mapper() {
        return (rs, rowNum) -> new VisitReview(
                rs.getLong("id"),
                rs.getLong("bill_id"),
                rs.getLong("chat_id"),
                instant(rs, "prompted_at"),
                nullableInt(rs, "stars"),
                rs.getString("thumb") == null ? null : VisitReview.Thumb.valueOf(rs.getString("thumb")),
                instant(rs, "tip_requested_at"),
                instant(rs, "rated_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
