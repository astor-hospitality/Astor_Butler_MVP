package museon_online.astor_butler.domain.booking;

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
public class TableReservationRepository {

    private static final Long DEFAULT_MANAGER_TELEGRAM_ID = 876857557L;

    private final JdbcTemplate jdbcTemplate;

    public List<VenueTable> findTables(String venueCode) {
        return jdbcTemplate.query("""
                SELECT *
                FROM venue_tables
                WHERE venue_code = ?
                ORDER BY sort_order, table_code
                """,
                venueTableMapper(),
                normalizeVenue(venueCode)
        );
    }

    public Optional<VenueTable> findTableByCode(String venueCode, String tableCode) {
        List<VenueTable> result = jdbcTemplate.query("""
                SELECT *
                FROM venue_tables
                WHERE venue_code = ? AND table_code = ?
                """,
                venueTableMapper(),
                normalizeVenue(venueCode),
                normalizeTableCode(tableCode)
        );
        return result.stream().findFirst();
    }

    public List<VenueTable> findAvailableTables(String venueCode, Instant startAt, Instant endAt, int partySize) {
        return findAvailableTables(venueCode, startAt, endAt, partySize, null);
    }

    public List<VenueTable> findAvailableTables(String venueCode, Instant startAt, Instant endAt, int partySize, String preferredZone) {
        String normalizedZone = normalizeZone(preferredZone);
        if (normalizedZone == null) {
            return jdbcTemplate.query("""
                    SELECT vt.*
                    FROM venue_tables vt
                    WHERE vt.venue_code = ?
                      AND vt.active = TRUE
                      AND vt.bookable = TRUE
                      AND vt.capacity_max >= ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM table_reservation_holds h
                          WHERE h.table_id = vt.id
                            AND h.status IN ('HELD', 'CONFIRMED')
                            AND h.start_at < ?
                            AND h.end_at > ?
                      )
                    ORDER BY vt.sort_order, vt.capacity_max, vt.table_code
                    """,
                    venueTableMapper(),
                    normalizeVenue(venueCode),
                    partySize,
                    timestamp(endAt),
                    timestamp(startAt)
            );
        }
        return jdbcTemplate.query("""
                SELECT vt.*
                FROM venue_tables vt
                WHERE vt.venue_code = ?
                  AND vt.active = TRUE
                  AND vt.bookable = TRUE
                  AND vt.capacity_max >= ?
                  AND vt.zone = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM table_reservation_holds h
                      WHERE h.table_id = vt.id
                        AND h.status IN ('HELD', 'CONFIRMED')
                        AND h.start_at < ?
                        AND h.end_at > ?
                  )
                ORDER BY vt.sort_order, vt.capacity_max, vt.table_code
                """,
                venueTableMapper(),
                normalizeVenue(venueCode),
                partySize,
                normalizedZone,
                timestamp(endAt),
                timestamp(startAt)
        );
    }

    public boolean hasActiveConflict(long tableId, Instant startAt, Instant endAt) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM table_reservation_holds
                WHERE table_id = ?
                  AND status IN ('HELD', 'CONFIRMED')
                  AND start_at < ?
                  AND end_at > ?
                """,
                Integer.class,
                tableId,
                timestamp(endAt),
                timestamp(startAt)
        );
        return count != null && count > 0;
    }

    public boolean hasActiveConflict(long tableId, Instant startAt, Instant endAt, Long excludedOrderId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM table_reservation_holds
                WHERE table_id = ?
                  AND status IN ('HELD', 'CONFIRMED')
                  AND (? IS NULL OR order_id <> ?)
                  AND start_at < ?
                  AND end_at > ?
                """,
                Integer.class,
                tableId,
                excludedOrderId,
                excludedOrderId,
                timestamp(endAt),
                timestamp(startAt)
        );
        return count != null && count > 0;
    }

    public List<VenueTable> findAlternativeTables(
            String venueCode,
            Instant startAt,
            Instant endAt,
            int partySize,
            String preferredZone,
            Long excludedOrderId
    ) {
        String normalizedZone = normalizeZone(preferredZone);
        if (normalizedZone == null) {
            return jdbcTemplate.query("""
                    SELECT vt.*
                    FROM venue_tables vt
                    WHERE vt.venue_code = ?
                      AND vt.active = TRUE
                      AND vt.bookable = TRUE
                      AND vt.capacity_max >= ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM table_reservation_holds h
                          WHERE h.table_id = vt.id
                            AND h.status IN ('HELD', 'CONFIRMED')
                            AND (? IS NULL OR h.order_id <> ?)
                            AND h.start_at < ?
                            AND h.end_at > ?
                      )
                    ORDER BY vt.sort_order, vt.capacity_max, vt.table_code
                    """,
                    venueTableMapper(),
                    normalizeVenue(venueCode),
                    partySize,
                    excludedOrderId,
                    excludedOrderId,
                    timestamp(endAt),
                    timestamp(startAt)
            );
        }
        return jdbcTemplate.query("""
                SELECT vt.*
                FROM venue_tables vt
                WHERE vt.venue_code = ?
                  AND vt.active = TRUE
                  AND vt.bookable = TRUE
                  AND vt.capacity_max >= ?
                  AND vt.zone = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM table_reservation_holds h
                      WHERE h.table_id = vt.id
                        AND h.status IN ('HELD', 'CONFIRMED')
                        AND (? IS NULL OR h.order_id <> ?)
                        AND h.start_at < ?
                        AND h.end_at > ?
                  )
                ORDER BY vt.sort_order, vt.capacity_max, vt.table_code
                """,
                venueTableMapper(),
                normalizeVenue(venueCode),
                partySize,
                normalizedZone,
                excludedOrderId,
                excludedOrderId,
                timestamp(endAt),
                timestamp(startAt)
        );
    }

    public TableReservationOrder createAwaitingManagerOrder(TableReservationCommand command, VenueTable table) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO table_reservation_orders (
                    chat_id, telegram_user_id, user_id, table_id, status, source,
                    requested_start_at, requested_end_at, party_size, guest_name,
                    guest_phone, guest_comment, preferred_zone, seating_preference,
                    manager_telegram_id, hostess_chat_id,
                    created_at, updated_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, 'TELEGRAM', ?, ?, ?, ?,
                    COALESCE(
                        NULLIF(TRIM(?), ''),
                        (
                            SELECT NULLIF(TRIM(u.phone), '')
                            FROM users u
                            WHERE u.telegram_id = ?
                            LIMIT 1
                        ),
                        (
                            SELECT NULLIF(TRIM(tp.phone_number), '')
                            FROM telegram_profiles tp
                            WHERE tp.telegram_user_id = ?
                               OR tp.chat_id = ?
                            ORDER BY tp.last_seen_at DESC NULLS LAST, tp.updated_at DESC NULLS LAST
                            LIMIT 1
                        )
                    ),
                    ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """,
                Long.class,
                command.chatId(),
                command.telegramUserId(),
                command.userId(),
                table.id(),
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION.name(),
                timestamp(command.requestedStartAt()),
                timestamp(command.requestedEndAt()),
                command.partySize(),
                command.guestName(),
                command.guestPhone(),
                command.telegramUserId(),
                command.telegramUserId(),
                command.chatId(),
                command.guestComment(),
                normalizeZone(command.preferredZone()),
                blankToNull(command.seatingPreference()),
                command.managerTelegramId() == null ? DEFAULT_MANAGER_TELEGRAM_ID : command.managerTelegramId(),
                command.hostessChatId()
        );
        createHold(id, table.id(), command.requestedStartAt(), command.requestedEndAt());
        return findOrder(id).orElseThrow();
    }

    public Optional<TableReservationOrder> findOrder(Long id) {
        List<TableReservationOrder> result = jdbcTemplate.query("""
                SELECT tro.*, vt.table_code, vt.display_name AS table_display_name
                FROM table_reservation_orders tro
                LEFT JOIN venue_tables vt ON vt.id = tro.table_id
                WHERE tro.id = ?
                """,
                orderMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public List<TableReservationOrder> findOrdersByChatId(Long chatId, int limit) {
        return jdbcTemplate.query("""
                SELECT tro.*, vt.table_code, vt.display_name AS table_display_name
                FROM table_reservation_orders tro
                LEFT JOIN venue_tables vt ON vt.id = tro.table_id
                WHERE tro.chat_id = ?
                ORDER BY tro.created_at DESC
                LIMIT ?
                """,
                orderMapper(),
                chatId,
                limit
        );
    }

    public List<TableReservationOrder> findActiveOrdersByChatId(Long chatId) {
        return jdbcTemplate.query("""
                SELECT tro.*, vt.table_code, vt.display_name AS table_display_name
                FROM table_reservation_orders tro
                LEFT JOIN venue_tables vt ON vt.id = tro.table_id
                WHERE tro.chat_id = ?
                  AND tro.status IN ('AWAITING_MANAGER_CONFIRMATION', 'CONFIRMED')
                ORDER BY tro.created_at DESC
                """,
                orderMapper(),
                chatId
        );
    }

    /** The same, but only what the guest holds at one venue. A reservation belongs to the venue of its table. */
    public List<TableReservationOrder> findActiveOrdersByChatId(Long chatId, String venueCode) {
        return jdbcTemplate.query("""
                SELECT tro.*, vt.table_code, vt.display_name AS table_display_name
                FROM table_reservation_orders tro
                JOIN venue_tables vt ON vt.id = tro.table_id
                WHERE tro.chat_id = ?
                  AND vt.venue_code = ?
                  AND tro.status IN ('AWAITING_MANAGER_CONFIRMATION', 'CONFIRMED')
                ORDER BY tro.requested_start_at
                """,
                orderMapper(),
                chatId,
                normalizeVenue(venueCode)
        );
    }

    /** Orders still alive locally that were also written to the venue's own system, oldest change first. */
    public List<TableReservationOrder> findActiveOrdersWithExternalId(int limit) {
        return findActiveOrdersWithExternalIdAfter(0L, limit);
    }

    public List<TableReservationOrder> findActiveOrdersWithExternalIdAfter(long afterId, int limit) {
        return jdbcTemplate.query("""
                SELECT tro.*, vt.table_code, vt.display_name AS table_display_name
                FROM table_reservation_orders tro
                LEFT JOIN venue_tables vt ON vt.id = tro.table_id
                WHERE tro.status IN ('AWAITING_MANAGER_CONFIRMATION', 'CONFIRMED')
                  AND tro.sbis_external_id IS NOT NULL
                  AND tro.sbis_external_id <> ''
                  AND tro.id > ?
                ORDER BY tro.id ASC
                LIMIT ?
                """,
                orderMapper(),
                afterId,
                Math.max(1, limit)
        );
    }

    public Optional<TableReservationOrder> findLatestAwaitingManagerConfirmation(String hostessChatId) {
        List<TableReservationOrder> result = jdbcTemplate.query("""
                SELECT tro.*, vt.table_code, vt.display_name AS table_display_name
                FROM table_reservation_orders tro
                LEFT JOIN venue_tables vt ON vt.id = tro.table_id
                WHERE tro.status = 'AWAITING_MANAGER_CONFIRMATION'
                  AND (
                      ? IS NULL
                      OR tro.hostess_chat_id IS NULL
                      OR tro.hostess_chat_id = ?
                  )
                ORDER BY tro.created_at DESC
                LIMIT 1
                """,
                orderMapper(),
                blankToNull(hostessChatId),
                blankToNull(hostessChatId)
        );
        return result.stream().findFirst();
    }

    public TableReservationOrder attachExternalId(Long id, String externalId) {
        jdbcTemplate.update("""
                UPDATE table_reservation_orders
                SET sbis_external_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                externalId,
                id
        );
        return findOrder(id).orElseThrow();
    }

    public TableReservationOrder confirm(Long id) {
        jdbcTemplate.update("""
                UPDATE table_reservation_orders
                SET status = 'CONFIRMED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                id
        );
        jdbcTemplate.update("""
                UPDATE table_reservation_holds
                SET status = 'CONFIRMED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                  AND status = 'HELD'
                """,
                id
        );
        return findOrder(id).orElseThrow();
    }

    public TableReservationOrder reject(Long id) {
        jdbcTemplate.update("""
                UPDATE table_reservation_orders
                SET status = 'REJECTED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                id
        );
        jdbcTemplate.update("""
                UPDATE table_reservation_holds
                SET status = 'RELEASED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                  AND status = 'HELD'
                """,
                id
        );
        return findOrder(id).orElseThrow();
    }

    public TableReservationOrder cancel(Long id) {
        jdbcTemplate.update("""
                UPDATE table_reservation_orders
                SET status = 'CANCELLED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                id
        );
        jdbcTemplate.update("""
                UPDATE table_reservation_holds
                SET status = 'RELEASED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                  AND status IN ('HELD', 'CONFIRMED')
                """,
                id
        );
        return findOrder(id).orElseThrow();
    }

    public TableReservationOrder changeReservation(Long id, TableReservationChangeCommand command, VenueTable table) {
        jdbcTemplate.update("""
                UPDATE table_reservation_orders
                SET table_id = ?,
                    status = 'AWAITING_MANAGER_CONFIRMATION',
                    requested_start_at = ?,
                    requested_end_at = ?,
                    party_size = ?,
                    preferred_zone = ?,
                    seating_preference = ?,
                    guest_comment = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                table.id(),
                timestamp(command.requestedStartAt()),
                timestamp(command.requestedEndAt()),
                command.partySize(),
                normalizeZone(command.preferredZone()),
                blankToNull(command.seatingPreference()),
                blankToNull(command.guestComment()),
                id
        );
        jdbcTemplate.update("""
                UPDATE table_reservation_holds
                SET status = 'RELEASED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                  AND status IN ('HELD', 'CONFIRMED')
                """,
                id
        );
        createHold(id, table.id(), command.requestedStartAt(), command.requestedEndAt());
        return findOrder(id).orElseThrow();
    }

    private void createHold(Long orderId, Long tableId, Instant startAt, Instant endAt) {
        jdbcTemplate.update("""
                INSERT INTO table_reservation_holds (
                    table_id, order_id, status, start_at, end_at, expires_at, created_at, updated_at
                )
                VALUES (?, ?, 'HELD', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                tableId,
                orderId,
                timestamp(startAt),
                timestamp(endAt),
                timestamp(startAt.plusSeconds(15 * 60L))
        );
    }

    private RowMapper<VenueTable> venueTableMapper() {
        return (rs, rowNum) -> new VenueTable(
                rs.getLong("id"),
                rs.getString("venue_code"),
                rs.getString("table_code"),
                rs.getString("display_name"),
                rs.getString("zone"),
                rs.getInt("capacity_min"),
                rs.getInt("capacity_max"),
                rs.getString("combinable_group"),
                rs.getBoolean("bookable"),
                rs.getBoolean("active"),
                rs.getInt("plan_page"),
                rs.getString("plan_ref"),
                rs.getInt("sort_order"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private RowMapper<TableReservationOrder> orderMapper() {
        return (rs, rowNum) -> new TableReservationOrder(
                rs.getLong("id"),
                rs.getLong("chat_id"),
                nullableLong(rs, "telegram_user_id"),
                nullableLong(rs, "user_id"),
                nullableLong(rs, "table_id"),
                rs.getString("table_code"),
                rs.getString("table_display_name"),
                rs.getString("preferred_zone"),
                rs.getString("seating_preference"),
                TableReservationStatus.valueOf(rs.getString("status")),
                rs.getString("source"),
                instant(rs, "requested_start_at"),
                instant(rs, "requested_end_at"),
                nullableInt(rs, "party_size"),
                rs.getString("guest_name"),
                rs.getString("guest_phone"),
                rs.getString("guest_comment"),
                nullableLong(rs, "manager_telegram_id"),
                nullableLong(rs, "manager_user_id"),
                rs.getString("hostess_chat_id"),
                rs.getString("sbis_external_id"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private String normalizeVenue(String venueCode) {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim().toUpperCase();
    }

    private String normalizeTableCode(String tableCode) {
        return tableCode == null ? null : tableCode.trim().toUpperCase();
    }

    private String normalizeZone(String zone) {
        return zone == null || zone.isBlank() ? null : zone.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
