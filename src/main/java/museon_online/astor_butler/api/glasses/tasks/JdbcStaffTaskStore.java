package museon_online.astor_butler.api.glasses.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;

/** Called inside the portal's tenant-locked transaction for every mutation, including delivery marks. */
public final class JdbcStaffTaskStore implements StaffTaskStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public JdbcStaffTaskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(StaffTask task) {
        jdbc.update("INSERT INTO astor_staff_tasks(task_id,tenant,assignee_staff_id,version,payload) VALUES (?,?,?,?,?)",
                task.taskId(), task.tenant(), task.assigneeStaffId(), task.version(), encode(task));
    }
    @Override public Optional<StaffTask> find(String id) {
        return jdbc.query("SELECT payload FROM astor_staff_tasks WHERE task_id=?", (rs, n) -> decode(rs.getString(1)), id)
                .stream().findFirst();
    }
    @Override public boolean replace(StaffTask task, long version) {
        return jdbc.update("UPDATE astor_staff_tasks SET assignee_staff_id=?,version=?,payload=?,changed_at=DEFAULT WHERE task_id=? AND tenant=? AND version=?",
                task.assigneeStaffId(), task.version(), encode(task), task.taskId(), task.tenant(), version) == 1;
    }
    @Override public List<StaffTask> byTenant(String tenant) {
        return bounded(jdbc.query("SELECT payload FROM astor_staff_tasks WHERE tenant=? ORDER BY changed_at DESC LIMIT 501",
                (rs, n) -> decode(rs.getString(1)), tenant));
    }
    public List<StaffTask> assignedTo(String tenant, String staff) {
        return bounded(jdbc.query("SELECT payload FROM astor_staff_tasks WHERE tenant=? AND assignee_staff_id=? ORDER BY changed_at DESC LIMIT 501",
                (rs, n) -> decode(rs.getString(1)), tenant, staff));
    }
    private List<StaffTask> bounded(List<StaffTask> tasks) {
        if (tasks.size() > 500) throw new StaffTaskFailure(503, "SNAPSHOT_LIMIT", "Staff snapshot exceeds the pilot limit; pagination is required");
        return tasks;
    }
    @Override public Page changedFor(String tenant, String staff, long cursor, int limit) {
        var changes = jdbc.query("SELECT payload,changed_at FROM astor_staff_tasks WHERE tenant=? AND assignee_staff_id=? AND changed_at>? ORDER BY changed_at LIMIT ?",
                (rs, n) -> new Change(decode(rs.getString(1)), rs.getLong(2)), tenant, staff, cursor, limit);
        return new Page(changes.stream().map(Change::task).toList(), changes.isEmpty() ? cursor : changes.getLast().cursor());
    }
    @Override public Optional<ProcessedEvent> event(String tenant, String id) {
        return jdbc.query("SELECT fingerprint,result_payload FROM astor_staff_task_events WHERE tenant=? AND event_id=?",
                (rs, n) -> new ProcessedEvent(tenant, id, rs.getString(1), decode(rs.getString(2))), tenant, id).stream().findFirst();
    }
    @Override public void remember(ProcessedEvent event) {
        jdbc.update("INSERT INTO astor_staff_task_events(tenant,event_id,fingerprint,result_payload) VALUES (?,?,?,?)",
                event.tenant(), event.eventId(), event.fingerprint(), encode(event.result()));
    }
    private String encode(StaffTask task) {
        try { return json.writeValueAsString(task); }
        catch (Exception e) { throw new IllegalStateException("Cannot persist staff task", e); }
    }
    private StaffTask decode(String value) {
        try { return json.readValue(value, StaffTask.class); }
        catch (Exception e) { throw new IllegalStateException("Cannot read staff task", e); }
    }
    private record Change(StaffTask task, long cursor) { }
}
