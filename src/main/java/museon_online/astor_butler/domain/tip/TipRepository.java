package museon_online.astor_butler.domain.tip;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TipRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<StaffProfile> findActiveStaff(String venueCode) {
        return jdbcTemplate.query("""
                SELECT *
                FROM staff_profiles
                WHERE venue_code = ?
                  AND active = TRUE
                ORDER BY display_name
                """,
                staffMapper(),
                normalizeVenue(venueCode)
        );
    }

    public Optional<StaffProfile> findStaff(Long id) {
        List<StaffProfile> result = jdbcTemplate.query("""
                SELECT *
                FROM staff_profiles
                WHERE id = ?
                """,
                staffMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public Optional<StaffProfile> findDefaultStaff(String venueCode) {
        List<StaffProfile> result = jdbcTemplate.query("""
                SELECT *
                FROM staff_profiles
                WHERE venue_code = ?
                  AND active = TRUE
                ORDER BY CASE WHEN metadata_json ->> 'default' = 'true' THEN 0 ELSE 1 END, display_name
                LIMIT 1
                """,
                staffMapper(),
                normalizeVenue(venueCode)
        );
        return result.stream().findFirst();
    }

    public TipOrder createDraft(TipOrderCommand command, StaffProfile staff) {
        return createDraft(command, staff, null);
    }

    /**
     * @param sbpUrl the link the guest will be given to pay by, known at draft time for a personal recipient (#161:
     *               the waiter of the table); null when the recipient is a staff profile or has no link yet
     */
    public TipOrder createDraft(TipOrderCommand command, StaffProfile staff, String sbpUrl) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tip_orders (
                    chat_id, telegram_user_id, user_id, venue_code, staff_id, staff_display_name,
                    status, source, amount_minor, currency, guest_name, guest_comment, sbp_url,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TELEGRAM', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                command.chatId(),
                command.telegramUserId(),
                command.userId(),
                normalizeVenue(command.venueCode()),
                staff == null ? command.staffId() : staff.id(),
                staff == null ? blankToNull(command.staffDisplayName()) : staff.displayName(),
                TipOrderStatus.AWAITING_GUEST_CONFIRMATION.name(),
                command.amountMinor(),
                currency(command.currency()),
                blankToNull(command.guestName()),
                blankToNull(command.guestComment()),
                blankToNull(sbpUrl)
        );
        return findOrder(id).orElseThrow();
    }

    public Optional<TipOrder> findOrder(Long id) {
        List<TipOrder> result = jdbcTemplate.query("""
                SELECT *
                FROM tip_orders
                WHERE id = ?
                """,
                orderMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public Optional<TipOrder> findLatestAwaitingGuestConfirmation(Long chatId) {
        List<TipOrder> result = jdbcTemplate.query("""
                SELECT *
                FROM tip_orders
                WHERE chat_id = ?
                  AND status = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                orderMapper(),
                chatId,
                TipOrderStatus.AWAITING_GUEST_CONFIRMATION.name()
        );
        return result.stream().findFirst();
    }

    public List<TipOrder> findOrdersByChatId(Long chatId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM tip_orders
                WHERE chat_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                orderMapper(),
                chatId,
                limit
        );
    }

    public TipOrder updateStatus(Long id, TipOrderStatus status) {
        jdbcTemplate.update("""
                UPDATE tip_orders
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                status.name(),
                id
        );
        return findOrder(id).orElseThrow();
    }

    private RowMapper<StaffProfile> staffMapper() {
        return (rs, rowNum) -> new StaffProfile(
                rs.getLong("id"),
                rs.getString("venue_code"),
                rs.getString("display_name"),
                rs.getString("role"),
                nullableLong(rs, "telegram_user_id"),
                rs.getString("phone"),
                rs.getString("sbp_link"),
                rs.getBoolean("active"),
                rs.getString("metadata_json"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private RowMapper<TipOrder> orderMapper() {
        return (rs, rowNum) -> new TipOrder(
                rs.getLong("id"),
                rs.getLong("chat_id"),
                nullableLong(rs, "telegram_user_id"),
                nullableLong(rs, "user_id"),
                rs.getString("venue_code"),
                nullableLong(rs, "staff_id"),
                rs.getString("staff_display_name"),
                TipOrderStatus.valueOf(rs.getString("status")),
                rs.getString("source"),
                nullableLong(rs, "amount_minor"),
                rs.getString("currency"),
                rs.getString("guest_name"),
                rs.getString("guest_comment"),
                rs.getString("sbp_url"),
                rs.getString("payment_external_id"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private String normalizeVenue(String venueCode) {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim().toUpperCase();
    }

    private String currency(String currency) {
        return currency == null || currency.isBlank() ? "RUB" : currency.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
