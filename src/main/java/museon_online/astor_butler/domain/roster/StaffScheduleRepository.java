package museon_online.astor_butler.domain.roster;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The recognised schedules ({@code staff_shift_schedules}, {@code staff_shift_entries}), the settled name forms
 * ({@code staff_name_aliases}, migration {@code 2026-10-16-staff-shift-schedule}) and the staff directory they point
 * at. Plain JDBC, like the staff portal that owns the directory.
 */
@Repository
@RequiredArgsConstructor
public class StaffScheduleRepository {

    public enum Status { CLARIFYING, PROPOSED, CONFIRMED, REJECTED }

    public enum EntryStatus { MATCHED, AMBIGUOUS, UNKNOWN, DISMISSED }

    public record Schedule(long id, String tenant, long chatId, int messageId, Status status, LocalDate firstDate,
                           LocalDate lastDate, int unreadable, Integer replyMessageId, String decidedBy) {
    }

    /** A row of a schedule; {@code candidates} are staff_ids, only while AMBIGUOUS. */
    public record Entry(long id, long scheduleId, LocalDate date, String rawName, StaffRole role, LocalTime start,
                        LocalTime end, EntryStatus status, String staffId, List<String> candidates) {
    }

    public record NewEntry(LocalDate date, String rawName, StaffRole role, LocalTime start, LocalTime end,
                           EntryStatus status, String staffId, List<String> candidates) {
    }

    private static final String SCHEDULE_COLUMNS =
            "id, tenant, chat_id, message_id, status, first_date, last_date, unreadable, reply_message_id, decided_by";

    private final JdbcTemplate jdbc;

    // ---- the directory -----------------------------------------------------------------------------------------

    public List<StaffNameMatcher.Member> activeMembers(String tenant) {
        return jdbc.query("""
                SELECT staff_id, display_name, role
                FROM astor_staff_members
                WHERE tenant = ?
                  AND active = TRUE
                ORDER BY display_name, staff_id
                LIMIT 201
                """,
                (rs, row) -> new StaffNameMatcher.Member(rs.getString("staff_id"), rs.getString("display_name"),
                        StaffRole.of(rs.getString("role"))),
                tenant
        );
    }

    public Map<String, String> aliases(String tenant) {
        Map<String, String> aliases = new HashMap<>();
        jdbc.query("SELECT alias, staff_id FROM staff_name_aliases WHERE tenant = ?",
                rs -> {
                    aliases.put(rs.getString("alias"), rs.getString("staff_id"));
                },
                tenant);
        return aliases;
    }

    /** The latest person to settle a name form wins. */
    public void saveAlias(String tenant, String alias, String staffId, String by) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        String key = alias.length() > StaffScheduleParser.MAX_NAME_LENGTH ? alias.substring(0, StaffScheduleParser.MAX_NAME_LENGTH) : alias;
        String sql = "UPDATE staff_name_aliases SET staff_id = ?, created_by = ?, created_at = CURRENT_TIMESTAMP WHERE tenant = ? AND alias = ?";
        if (jdbc.update(sql, staffId, by, tenant, key) > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO staff_name_aliases(tenant, alias, staff_id, created_by) VALUES (?, ?, ?, ?)",
                    tenant, key, staffId, by);
        } catch (DuplicateKeyException e) {
            jdbc.update(sql, staffId, by, tenant, key);
        }
    }

    // ---- schedules ---------------------------------------------------------------------------------------------

    public boolean exists(String tenant, long chatId, int messageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM staff_shift_schedules WHERE tenant = ? AND chat_id = ? AND message_id = ?",
                Boolean.class, tenant, chatId, messageId));
    }

    /** Null when this picture was already recognised (the same update twice). */
    public Long insert(String tenant, long chatId, int messageId, Status status, LocalDate firstDate, LocalDate lastDate,
                       int unreadable, String postedBy, String model, List<NewEntry> entries) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO staff_shift_schedules(tenant, chat_id, message_id, status, first_date, last_date,
                                                          unreadable, posted_by, model)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                statement.setString(1, tenant);
                statement.setLong(2, chatId);
                statement.setInt(3, messageId);
                statement.setString(4, status.name());
                statement.setDate(5, Date.valueOf(firstDate));
                statement.setDate(6, Date.valueOf(lastDate));
                statement.setInt(7, unreadable);
                statement.setString(8, cut(postedBy, 160));
                statement.setString(9, cut(model, 120));
                return statement;
            }, key);
        } catch (DuplicateKeyException e) {
            return null;
        }
        long id = key.getKey().longValue();
        jdbc.batchUpdate("""
                INSERT INTO staff_shift_entries(schedule_id, shift_date, raw_name, role, starts_at, ends_at,
                                                match_status, staff_id, candidates)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entries,
                200,
                (statement, entry) -> {
                    statement.setLong(1, id);
                    statement.setDate(2, Date.valueOf(entry.date()));
                    statement.setString(3, entry.rawName());
                    statement.setString(4, entry.role() == null ? null : entry.role().name());
                    statement.setTime(5, entry.start() == null ? null : Time.valueOf(entry.start()));
                    statement.setTime(6, entry.end() == null ? null : Time.valueOf(entry.end()));
                    statement.setString(7, entry.status().name());
                    statement.setString(8, entry.staffId());
                    statement.setString(9, entry.candidates() == null || entry.candidates().isEmpty()
                            ? null : String.join(",", entry.candidates()));
                });
        return id;
    }

    public Optional<Schedule> schedule(long id) {
        return jdbc.query("SELECT " + SCHEDULE_COLUMNS + " FROM staff_shift_schedules WHERE id = ?", scheduleMapper(), id)
                .stream().findFirst();
    }

    public List<Entry> entries(long scheduleId) {
        return jdbc.query("""
                SELECT id, schedule_id, shift_date, raw_name, role, starts_at, ends_at, match_status, staff_id, candidates
                FROM staff_shift_entries
                WHERE schedule_id = ?
                ORDER BY shift_date, id
                """, entryMapper(), scheduleId);
    }

    public Optional<Entry> entry(long id) {
        return jdbc.query("""
                SELECT id, schedule_id, shift_date, raw_name, role, starts_at, ends_at, match_status, staff_id, candidates
                FROM staff_shift_entries
                WHERE id = ?
                """, entryMapper(), id).stream().findFirst();
    }

    /** Every still open row of that name in the schedule gets the member; a row without a role takes theirs. */
    public int resolve(long scheduleId, String rawName, String staffId, StaffRole memberRole) {
        return jdbc.update("""
                UPDATE staff_shift_entries
                SET match_status = 'MATCHED', staff_id = ?, role = COALESCE(role, ?), candidates = NULL
                WHERE schedule_id = ? AND raw_name = ? AND match_status = 'AMBIGUOUS'
                """, staffId, memberRole == null ? null : memberRole.name(), scheduleId, rawName);
    }

    public int dismiss(long scheduleId, String rawName) {
        return jdbc.update("""
                UPDATE staff_shift_entries
                SET match_status = 'DISMISSED', candidates = NULL
                WHERE schedule_id = ? AND raw_name = ? AND match_status = 'AMBIGUOUS'
                """, scheduleId, rawName);
    }

    /** Moves the schedule on only from the expected status: of two people pressing at once, one wins. */
    public boolean transition(long id, Status from, Status to, String decidedBy) {
        boolean decision = to == Status.CONFIRMED || to == Status.REJECTED;
        return jdbc.update("""
                UPDATE staff_shift_schedules
                SET status = ?,
                    decided_by = CASE WHEN ? THEN ? ELSE decided_by END,
                    decided_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE decided_at END
                WHERE id = ? AND status = ?
                """, to.name(), decision, cut(decidedBy, 160), decision, id, from.name()) > 0;
    }

    public void replyMessage(long id, int messageId) {
        jdbc.update("UPDATE staff_shift_schedules SET reply_message_id = ? WHERE id = ?", messageId, id);
    }

    // ---- who is on shift ---------------------------------------------------------------------------------------

    /** The latest confirmed schedule whose days cover the date: it answers for that day. */
    public Optional<Schedule> governing(String tenant, LocalDate date) {
        return jdbc.query("""
                SELECT %s
                FROM staff_shift_schedules
                WHERE tenant = ? AND status = 'CONFIRMED' AND first_date <= ? AND last_date >= ?
                ORDER BY decided_at DESC, id DESC
                LIMIT 1
                """.formatted(SCHEDULE_COLUMNS), scheduleMapper(), tenant, Date.valueOf(date), Date.valueOf(date))
                .stream().findFirst();
    }

    /** The newest schedule of any status covering the date: one still waiting for «Верно» is worth a reminder. */
    public Optional<Schedule> newest(String tenant, LocalDate date) {
        return jdbc.query("""
                SELECT %s
                FROM staff_shift_schedules
                WHERE tenant = ? AND first_date <= ? AND last_date >= ?
                ORDER BY id DESC
                LIMIT 1
                """.formatted(SCHEDULE_COLUMNS), scheduleMapper(), tenant, Date.valueOf(date), Date.valueOf(date))
                .stream().findFirst();
    }

    /** The settled rows of the day in that schedule, of members still active in the directory. */
    public List<StaffRoster.OnShift> onShift(String tenant, long scheduleId, LocalDate date) {
        return jdbc.query("""
                SELECT e.staff_id, m.display_name, COALESCE(e.role, m.role) AS role, e.shift_date, e.starts_at, e.ends_at
                FROM staff_shift_entries e
                JOIN astor_staff_members m ON m.tenant = ? AND m.staff_id = e.staff_id AND m.active = TRUE
                WHERE e.schedule_id = ? AND e.shift_date = ? AND e.match_status = 'MATCHED'
                ORDER BY e.starts_at NULLS LAST, m.display_name
                """,
                (rs, row) -> new StaffRoster.OnShift(
                        rs.getString("staff_id"),
                        rs.getString("display_name"),
                        StaffRole.of(rs.getString("role")),
                        rs.getDate("shift_date").toLocalDate(),
                        time(rs, "starts_at"),
                        time(rs, "ends_at")),
                tenant, scheduleId, Date.valueOf(date));
    }

    private static RowMapper<Schedule> scheduleMapper() {
        return (rs, row) -> new Schedule(
                rs.getLong("id"),
                rs.getString("tenant"),
                rs.getLong("chat_id"),
                rs.getInt("message_id"),
                Status.valueOf(rs.getString("status")),
                rs.getDate("first_date").toLocalDate(),
                rs.getDate("last_date").toLocalDate(),
                rs.getInt("unreadable"),
                rs.getObject("reply_message_id", Integer.class),
                rs.getString("decided_by"));
    }

    private static RowMapper<Entry> entryMapper() {
        return (rs, row) -> {
            String candidates = rs.getString("candidates");
            return new Entry(
                    rs.getLong("id"),
                    rs.getLong("schedule_id"),
                    rs.getDate("shift_date").toLocalDate(),
                    rs.getString("raw_name"),
                    StaffRole.of(rs.getString("role")),
                    time(rs, "starts_at"),
                    time(rs, "ends_at"),
                    EntryStatus.valueOf(rs.getString("match_status")),
                    rs.getString("staff_id"),
                    candidates == null || candidates.isBlank() ? List.of() : Arrays.asList(candidates.split(",")));
        };
    }

    private static LocalTime time(ResultSet rs, String column) throws SQLException {
        Time time = rs.getTime(column);
        return time == null ? null : time.toLocalTime();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
