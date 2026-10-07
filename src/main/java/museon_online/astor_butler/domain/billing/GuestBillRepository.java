package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GuestBillRepository {

    private final JdbcTemplate jdbcTemplate;

    public GuestBill insert(BillDraft draft) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO guest_bills (
                        chat_id, telegram_user_id, venue_code, kind, source, table_reservation_id,
                        status, pay_state, estimate_minor, currency, price_source, visit_ends_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUB', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    new String[]{"id"}
            );
            statement.setLong(1, draft.chatId());
            statement.setObject(2, draft.telegramUserId(), Types.BIGINT);
            statement.setString(3, normalizeVenue(draft.venueCode()));
            statement.setString(4, draft.kind().name());
            statement.setString(5, blankToNull(draft.source()) == null ? "TELEGRAM" : draft.source().trim().toUpperCase());
            statement.setObject(6, draft.tableReservationId(), Types.BIGINT);
            statement.setString(7, GuestBillStatus.ESTIMATED.name());
            statement.setString(8, BillPayState.NOT_REPORTED.name());
            statement.setObject(9, draft.estimate().totalMinor(), Types.BIGINT);
            statement.setString(10, blankToNull(draft.priceSource()));
            statement.setTimestamp(11, draft.visitEndsAt() == null ? null : Timestamp.from(draft.visitEndsAt()));
            return statement;
        }, keyHolder);
        Long id = keyHolder.getKey().longValue();

        List<OrderEstimate.Line> lines = draft.estimate().lines();
        for (int index = 0; index < lines.size(); index++) {
            OrderEstimate.Line line = lines.get(index);
            jdbcTemplate.update("""
                    INSERT INTO guest_bill_lines (bill_id, line_no, item_code, title, quantity, unit_price_minor, included)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    index + 1,
                    blankToNull(line.code()),
                    line.title(),
                    line.quantity(),
                    line.unitPriceMinor(),
                    line.included()
            );
        }
        return find(id).orElseThrow();
    }

    public Optional<GuestBill> find(Long id) {
        List<GuestBill> result = jdbcTemplate.query("""
                SELECT *
                FROM guest_bills
                WHERE id = ?
                """,
                billMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public Optional<GuestBill> findByReservation(GuestBillKind kind, Long tableReservationId) {
        List<GuestBill> result = jdbcTemplate.query("""
                SELECT *
                FROM guest_bills
                WHERE kind = ?
                  AND table_reservation_id = ?
                """,
                billMapper(),
                kind.name(),
                tableReservationId
        );
        return result.stream().findFirst();
    }

    public Optional<GuestBill> findByExternalOrder(String externalProvider, String externalOrderId) {
        List<GuestBill> result = jdbcTemplate.query("""
                SELECT *
                FROM guest_bills
                WHERE external_provider = ?
                  AND external_order_id = ?
                """,
                billMapper(),
                externalProvider,
                externalOrderId
        );
        return result.stream().findFirst();
    }

    public List<GuestBill> findByChatId(Long chatId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM guest_bills
                WHERE chat_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                billMapper(),
                chatId,
                limit
        );
    }

    public List<GuestBillLine> findLines(Long billId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM guest_bill_lines
                WHERE bill_id = ?
                ORDER BY line_no
                """,
                lineMapper(),
                billId
        );
    }

    public GuestBill attachExternalOrder(Long id, String externalProvider, String externalOrderId) {
        jdbcTemplate.update("""
                UPDATE guest_bills
                SET external_provider = ?,
                    external_order_id = ?,
                    status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                externalProvider,
                externalOrderId,
                GuestBillStatus.ISSUED.name(),
                id
        );
        return find(id).orElseThrow();
    }

    public GuestBill updateVenueState(Long id, GuestBillStatus status, BillPayState payState, Long venueAmountMinor) {
        jdbcTemplate.update("""
                UPDATE guest_bills
                SET status = ?,
                    pay_state = ?,
                    venue_amount_minor = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                status.name(),
                payState.name(),
                venueAmountMinor,
                id
        );
        return find(id).orElseThrow();
    }

    /** Issued bills whose guest has not been sent the venue's payment link yet, oldest first. */
    public List<GuestBill> findAwaitingPaymentLink(int limit) {
        return findAwaitingPaymentLinkAfter(0L, limit);
    }

    public List<GuestBill> findAwaitingPaymentLinkAfter(long afterId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM guest_bills
                WHERE status = ?
                  AND payment_link_sent_at IS NULL
                  AND external_order_id IS NOT NULL
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """,
                billMapper(),
                GuestBillStatus.ISSUED.name(),
                afterId,
                limit
        );
    }

    /** Bills whose visit ended inside the window and that have no review row yet, oldest first. */
    public List<GuestBill> findVisitsEndedWithoutReview(Instant from, Instant to, int limit) {
        return jdbcTemplate.query("""
                SELECT b.*
                FROM guest_bills b
                WHERE b.visit_ends_at >= ?
                  AND b.visit_ends_at <= ?
                  AND b.status <> ?
                  AND NOT EXISTS (SELECT 1 FROM visit_reviews r WHERE r.bill_id = b.id)
                ORDER BY b.visit_ends_at, b.id
                LIMIT ?
                """,
                billMapper(),
                Timestamp.from(from),
                Timestamp.from(to),
                GuestBillStatus.CANCELLED.name(),
                limit
        );
    }

    public GuestBill markPaymentLinkSent(Long id) {
        jdbcTemplate.update("""
                UPDATE guest_bills
                SET payment_link_sent_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                id
        );
        return find(id).orElseThrow();
    }

    public GuestBill markPaidNotified(Long id) {
        jdbcTemplate.update("""
                UPDATE guest_bills
                SET paid_notified_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                id
        );
        return find(id).orElseThrow();
    }

    public GuestBill updateStatus(Long id, GuestBillStatus status) {
        jdbcTemplate.update("""
                UPDATE guest_bills
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                status.name(),
                id
        );
        return find(id).orElseThrow();
    }

    private RowMapper<GuestBill> billMapper() {
        return (rs, rowNum) -> new GuestBill(
                rs.getLong("id"),
                rs.getLong("chat_id"),
                nullableLong(rs, "telegram_user_id"),
                rs.getString("venue_code"),
                GuestBillKind.valueOf(rs.getString("kind")),
                rs.getString("source"),
                nullableLong(rs, "table_reservation_id"),
                rs.getString("external_provider"),
                rs.getString("external_order_id"),
                GuestBillStatus.valueOf(rs.getString("status")),
                BillPayState.valueOf(rs.getString("pay_state")),
                nullableLong(rs, "estimate_minor"),
                nullableLong(rs, "venue_amount_minor"),
                rs.getString("currency"),
                rs.getString("price_source"),
                instant(rs, "visit_ends_at"),
                instant(rs, "payment_link_sent_at"),
                instant(rs, "paid_notified_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private RowMapper<GuestBillLine> lineMapper() {
        return (rs, rowNum) -> new GuestBillLine(
                rs.getLong("id"),
                rs.getLong("bill_id"),
                rs.getInt("line_no"),
                rs.getString("item_code"),
                rs.getString("title"),
                rs.getInt("quantity"),
                nullableLong(rs, "unit_price_minor"),
                rs.getBoolean("included")
        );
    }

    private String normalizeVenue(String venueCode) {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
