package museon_online.astor_butler.domain.entry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Plain SQL over guest_entry_events and a read of astor_staff_members; runs on PostgreSQL and on H2 in tests. */
@Repository
public class GuestEntryEventRepository {

    private final JdbcTemplate jdbc;

    public GuestEntryEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tenant, long chatId, String staffId, String tableCode, String source, Instant at) {
        jdbc.update("""
                INSERT INTO guest_entry_events (tenant, chat_id, staff_id, table_code, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, blankToNull(tenant), chatId, staffId, blankToNull(tableCode), source, Timestamp.from(at));
    }

    /** The most recent entry of this chat not older than {@code since}: the one a request made now belongs to. */
    public Optional<GuestEntryEvent> latestForChat(long chatId, Instant since) {
        return jdbc.query("""
                SELECT id, tenant, chat_id, staff_id, table_code, source, created_at
                FROM guest_entry_events
                WHERE chat_id = ? AND created_at >= ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, mapper(), chatId, Timestamp.from(since)).stream().findFirst();
    }

    /** The name the staff directory shows for this id; by tenant when one is configured, else the freshest row. */
    public Optional<String> staffDisplayName(String tenant, String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Optional.empty();
        }
        if (tenant == null || tenant.isBlank()) {
            return jdbc.queryForList("""
                    SELECT display_name FROM astor_staff_members
                    WHERE staff_id = ?
                    ORDER BY active DESC, updated_at DESC
                    LIMIT 1
                    """, String.class, staffId).stream().findFirst();
        }
        return jdbc.queryForList(
                "SELECT display_name FROM astor_staff_members WHERE tenant = ? AND staff_id = ? LIMIT 1",
                String.class, tenant, staffId).stream().findFirst();
    }

    private RowMapper<GuestEntryEvent> mapper() {
        return (rs, rowNum) -> new GuestEntryEvent(
                rs.getLong("id"),
                rs.getString("tenant"),
                rs.getLong("chat_id"),
                rs.getString("staff_id"),
                rs.getString("table_code"),
                rs.getString("source"),
                instant(rs, "created_at")
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
