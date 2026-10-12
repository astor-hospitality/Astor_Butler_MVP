package museon_online.astor_butler.domain.tips;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The staff directory ({@code astor_staff_members}) from the tips side and the waiter-to-table assignments for a
 * shift day ({@code table_waiter_assignments}, migration {@code 2026-10-12-tips-personal}). Plain JDBC, like the
 * staff portal that owns the directory.
 */
@Repository
@RequiredArgsConstructor
public class TipsPersonalRepository {

    private static final String MEMBER_COLUMNS =
            "m.staff_id, m.display_name, m.role, m.active, m.shift_open, m.tip_link, m.tip_note";

    private final JdbcTemplate jdbc;

    /** Everyone active in the directory, by name. */
    public List<TipStaffMember> activeMembers(String tenant) {
        return jdbc.query("""
                SELECT %s
                FROM astor_staff_members m
                WHERE m.tenant = ?
                  AND m.active = TRUE
                ORDER BY m.display_name, m.staff_id
                LIMIT 201
                """.formatted(MEMBER_COLUMNS),
                memberMapper(),
                tenant
        );
    }

    public Optional<TipStaffMember> member(String tenant, String staffId) {
        return jdbc.query("""
                SELECT %s
                FROM astor_staff_members m
                WHERE m.tenant = ?
                  AND m.staff_id = ?
                """.formatted(MEMBER_COLUMNS),
                memberMapper(),
                tenant,
                staffId
        ).stream().findFirst();
    }

    /** Sets or clears (null link) the member's tip link and note. False when there is no such member. */
    public boolean setTipLink(String tenant, String staffId, String link, String note) {
        return jdbc.update("""
                UPDATE astor_staff_members
                SET tip_link = ?,
                    tip_note = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant = ?
                  AND staff_id = ?
                """,
                blankToNull(link),
                blankToNull(note),
                tenant,
                staffId
        ) > 0;
    }

    /** The table's waiter for the day; a second call for the same table and day replaces the first. */
    public void assign(String tenant, String tableCode, LocalDate shiftDate, String staffId, String assignedBy) {
        if (updateAssignment(tenant, tableCode, shiftDate, staffId, assignedBy) > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO table_waiter_assignments (tenant, table_code, shift_date, staff_id, assigned_by, assigned_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    tenant,
                    tableCode,
                    Date.valueOf(shiftDate),
                    staffId,
                    blankToNull(assignedBy)
            );
        } catch (DuplicateKeyException raced) {
            // Two managers assigned the same table at once: the later word wins, as with a second command.
            updateAssignment(tenant, tableCode, shiftDate, staffId, assignedBy);
        }
    }

    private int updateAssignment(String tenant, String tableCode, LocalDate shiftDate, String staffId, String assignedBy) {
        return jdbc.update("""
                UPDATE table_waiter_assignments
                SET staff_id = ?,
                    assigned_by = ?,
                    assigned_at = CURRENT_TIMESTAMP
                WHERE tenant = ?
                  AND table_code = ?
                  AND shift_date = ?
                """,
                staffId,
                blankToNull(assignedBy),
                tenant,
                tableCode,
                Date.valueOf(shiftDate)
        );
    }

    public boolean unassign(String tenant, String tableCode, LocalDate shiftDate) {
        return jdbc.update("""
                DELETE FROM table_waiter_assignments
                WHERE tenant = ?
                  AND table_code = ?
                  AND shift_date = ?
                """,
                tenant,
                tableCode,
                Date.valueOf(shiftDate)
        ) > 0;
    }

    /** The active member assigned to the table on that day, if any. */
    public Optional<TipStaffMember> waiterOfTable(String tenant, String tableCode, LocalDate shiftDate) {
        return jdbc.query("""
                SELECT %s
                FROM table_waiter_assignments a
                JOIN astor_staff_members m ON m.tenant = a.tenant AND m.staff_id = a.staff_id
                WHERE a.tenant = ?
                  AND a.table_code = ?
                  AND a.shift_date = ?
                  AND m.active = TRUE
                """.formatted(MEMBER_COLUMNS),
                memberMapper(),
                tenant,
                tableCode,
                Date.valueOf(shiftDate)
        ).stream().findFirst();
    }

    /** All assignments of the day, tables in their natural order (1, 2, …, 10, not 1, 10, 2). */
    public List<TableWaiterAssignment> assignments(String tenant, LocalDate shiftDate) {
        return jdbc.query("""
                SELECT a.tenant, a.table_code, a.shift_date, a.staff_id, m.display_name, a.assigned_by, a.assigned_at
                FROM table_waiter_assignments a
                JOIN astor_staff_members m ON m.tenant = a.tenant AND m.staff_id = a.staff_id
                WHERE a.tenant = ?
                  AND a.shift_date = ?
                ORDER BY LENGTH(a.table_code), a.table_code
                """,
                (rs, rowNum) -> new TableWaiterAssignment(
                        rs.getString("tenant"),
                        rs.getString("table_code"),
                        rs.getDate("shift_date").toLocalDate(),
                        rs.getString("staff_id"),
                        rs.getString("display_name"),
                        rs.getString("assigned_by"),
                        instant(rs, "assigned_at")
                ),
                tenant,
                Date.valueOf(shiftDate)
        );
    }

    private RowMapper<TipStaffMember> memberMapper() {
        return (rs, rowNum) -> new TipStaffMember(
                rs.getString("staff_id"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getBoolean("active"),
                rs.getBoolean("shift_open"),
                rs.getString("tip_link"),
                rs.getString("tip_note")
        );
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
