package museon_online.astor_butler.domain.booking;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Plain SQL over table_reservation_morning_checks; runs on PostgreSQL and on H2 in tests. */
@Repository
public class BookingMorningCheckRepository {

    private static final int ERROR_LENGTH = 160;

    private final JdbcTemplate jdbc;

    public BookingMorningCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the morning check of a reservation for this instance. False when it is already taken, whether the message
     * was sent, failed, or is still in flight on another instance: the primary key decides, not a read before a write.
     */
    public boolean claim(long reservationId, long chatId, LocalDate visitDate) {
        try {
            return jdbc.update("""
                    INSERT INTO table_reservation_morning_checks (reservation_id, chat_id, visit_date)
                    VALUES (?, ?, ?)
                    """, reservationId, chatId, Date.valueOf(visitDate)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void markSent(long reservationId, Instant at) {
        jdbc.update("UPDATE table_reservation_morning_checks SET sent_at = ?, delivery_error = NULL WHERE reservation_id = ?",
                Timestamp.from(at), reservationId);
    }

    public void markFailed(long reservationId, String error) {
        String bounded = error == null ? "UNKNOWN" : error.substring(0, Math.min(error.length(), ERROR_LENGTH));
        jdbc.update("UPDATE table_reservation_morning_checks SET delivery_error = ? WHERE reservation_id = ?",
                bounded, reservationId);
    }

    /** Records the button the guest pressed; a reconfirmation also stamps guest_reconfirmed_at. */
    public void answer(long reservationId, String answer, Instant at, boolean reconfirmed) {
        if (reconfirmed) {
            jdbc.update("""
                    UPDATE table_reservation_morning_checks
                    SET answer = ?, answered_at = ?, guest_reconfirmed_at = ?
                    WHERE reservation_id = ?
                    """, answer, Timestamp.from(at), Timestamp.from(at), reservationId);
            return;
        }
        jdbc.update("""
                UPDATE table_reservation_morning_checks
                SET answer = ?, answered_at = ?
                WHERE reservation_id = ?
                """, answer, Timestamp.from(at), reservationId);
    }

    public Optional<BookingMorningCheck> find(long reservationId) {
        return jdbc.query("SELECT * FROM table_reservation_morning_checks WHERE reservation_id = ?", mapper(), reservationId)
                .stream().findFirst();
    }

    public List<BookingMorningCheck> findByVisitDate(LocalDate visitDate) {
        return jdbc.query("SELECT * FROM table_reservation_morning_checks WHERE visit_date = ? ORDER BY reservation_id",
                mapper(), Date.valueOf(visitDate));
    }

    private RowMapper<BookingMorningCheck> mapper() {
        return (rs, rowNum) -> new BookingMorningCheck(
                rs.getLong("reservation_id"),
                rs.getLong("chat_id"),
                rs.getObject("visit_date", LocalDate.class),
                instant(rs, "sent_at"),
                rs.getString("delivery_error"),
                rs.getString("answer"),
                instant(rs, "answered_at"),
                instant(rs, "guest_reconfirmed_at"),
                instant(rs, "created_at")
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
