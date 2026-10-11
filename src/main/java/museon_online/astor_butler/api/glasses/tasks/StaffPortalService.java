package museon_online.astor_butler.api.glasses.tasks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL is authoritative; never fall back to demo data or an in-memory store. */
public final class StaffPortalService {
    public record Member(String staffId, String displayName, String role, boolean active, String shift, String deviceId) { }
    public record Audit(String type, String actor, Instant at, long version) { }
    public record Dashboard(String tenant, List<Member> staff, List<StaffTask> tasks, boolean manageStaff) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcStaffTaskStore store;
    private final StaffTaskService rules;
    private final JdbcStaffEvidenceRecords proofs;
    /** Null while no storage for proof photos is configured: every evidence route then answers 503. */
    private final StaffEvidenceSettings evidence;
    private final Clock clock;

    public StaffPortalService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, null, Clock.systemUTC());
    }
    public StaffPortalService(JdbcTemplate jdbc, PlatformTransactionManager manager, StaffEvidenceSettings evidence, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
        this.store = new JdbcStaffTaskStore(jdbc);
        this.rules = new StaffTaskService(store, this::onShift, clock);
        this.proofs = new JdbcStaffEvidenceRecords(jdbc);
        this.evidence = evidence;
        this.clock = clock;
    }
    public Dashboard dashboard(StaffScope scope) {
        manager(scope);
        return new Dashboard(scope.tenant(), members(scope.tenant()), rules.list(scope), scope.role() == StaffScope.Role.MANAGER);
    }
    public List<StaffTask> snapshot(StaffScope scope) {
        active(scope);
        // Full replacement avoids stale assignments after reassignment; no incremental cursor advertised.
        return onShift(scope.tenant(), scope.staffId()) ? store.assignedTo(scope.tenant(), scope.staffId()) : List.of();
    }
    public List<Audit> history(StaffScope scope, String taskId) {
        manager(scope);
        store.find(taskId).filter(t -> t.tenant().equals(scope.tenant()))
                .orElseThrow(() -> new StaffTaskFailure(404, "NOT_FOUND", "Task was not found"));
        return jdbc.query("SELECT type,actor,at,version_after FROM astor_staff_task_audit WHERE tenant=? AND task_id=? ORDER BY at DESC LIMIT 100",
                (rs, n) -> new Audit(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getLong(4)), scope.tenant(), taskId);
    }
    /** What an event id already did, so a repeated request answers with its task instead of acting again. */
    public java.util.Optional<StaffTask> replay(String tenant, String eventId) {
        if (tenant == null || eventId == null || eventId.isBlank()) return java.util.Optional.empty();
        return store.event(tenant, eventId).map(StaffTaskStore.ProcessedEvent::result);
    }
    public StaffTask create(StaffScope scope, String id, StaffTaskService.Draft draft) {
        if (draft != null) {
            bounded(draft.tableCode(), 32); bounded(draft.title(), 120); bounded(draft.instruction(), 1000);
            bounded(draft.sourceRef(), 160);
            if (draft.stages() != null) for (var stage : draft.stages()) if (stage != null) {
                bounded(stage.code(), 32); bounded(stage.title(), 120);
            }
        }
        return execute(scope, id, "ASSIGNED", () -> rules.create(scope, id, draft));
    }
    public StaffTask reassign(StaffScope scope, String task, String id, long version, String staff) {
        return execute(scope, id, "REASSIGN", () -> rules.reassign(scope, task, id, version, staff));
    }
    public StaffTask cancel(StaffScope scope, String task, String id, long version) {
        return execute(scope, id, "CANCEL", () -> rules.cancel(scope, task, id, version));
    }
    public StaffTask resolveHelp(StaffScope scope, String task, String id, long version) {
        return execute(scope, id, "HELP_RESOLVED", () -> rules.resolveHelp(scope, task, id, version));
    }
    public StaffTask command(StaffScope scope, String task, String id, StaffTaskService.Command type, long version, String stage) {
        return execute(scope, id, type == null ? "COMMAND" : type.name(), () -> rules.command(scope, task, id, type, version, stage));
    }
    public StaffTask delivery(StaffScope scope, String task, String id, StaffTaskService.Delivery type) {
        return execute(scope, id, type == null ? "DELIVERY" : type.name(), () -> rules.delivery(scope, task, id, type));
    }
    private StaffTask execute(StaffScope scope, String id, String type, Supplier<StaffTask> action) {
        return locked(scope, () -> {
            active(scope);
            boolean replay = id != null && store.event(scope.tenant(), id).isPresent();
            StaffTask result = action.get();
            if (!replay) audit(scope, id, type, result);
            return result;
        });
    }
    private void audit(StaffScope scope, String id, String type, StaffTask result) {
        jdbc.update("INSERT INTO astor_staff_task_audit(tenant,event_id,task_id,actor,type,version_after) VALUES (?,?,?,?,?,?)",
                scope.tenant(), id, result.taskId(), scope.staffId(), type, result.version());
    }

    /* ---------- Proof photos: docs/operations/STAFF_TASK_EVIDENCE.md ---------- */

    /** What an upload may be, or empty while proof photos have no storage. */
    public Optional<StaffEvidence.Limits> evidenceLimits() {
        return Optional.ofNullable(evidence).map(StaffEvidenceSettings::limits);
    }
    /** Asked before a photo is read from the wire: only this task's assignee with an open shift goes on. */
    public void evidenceUpload(StaffScope scope, String task) {
        assignee(scope, task);
    }
    /**
     * Stores a photo and counts it on its stage. The file goes to the storage first; only then, in one
     * tenant-locked transaction, the stage gets its photo, the task its next version, the audit its line and
     * the file its row. A photo that was not stored is never counted, so a stage that requires one stays open.
     */
    public StaffEvidence.Receipt evidence(StaffScope scope, String task, String id, String stage, Instant captured,
                                          String contentType, byte[] photo) {
        StaffEvidenceSettings settings = storing();
        // Rights, the file itself, a repeated event and the state of the stage are settled by reading only,
        // without the tenant lock: nothing is written for a request that will be refused, and no lock waits
        // for the storage.
        StaffTask own = assignee(scope, task);
        bounded(stage, 32);
        settings.accept(contentType, photo);
        Instant capturedAt = captured == null ? null : captured.truncatedTo(ChronoUnit.MILLIS);
        String sha256 = StaffEvidenceStorage.sha256(photo);
        Optional<StaffTask> before = rules.evidenceReplay(scope, task, id, stage, capturedAt, sha256);
        if (before.isPresent()) return new StaffEvidence.Receipt(stored(scope, id), before.get());
        String evidenceId = UUID.randomUUID().toString();
        // The key is made here from the stored task and a fresh id; nothing in it comes from the request.
        String key = StaffEvidenceStorage.key(scope.tenant(), own.taskId(), evidenceId, contentType);
        settings.storage().put(key, photo, contentType);
        // If the transaction below refuses after all (the task was reassigned meanwhile), the object stays
        // without a row: nothing can reach it, and the lifecycle rule of the bucket removes it.
        return locked(scope, () -> {
            active(scope);
            boolean replay = store.event(scope.tenant(), id).isPresent();
            StaffTask result = rules.evidence(scope, task, id, stage, capturedAt, sha256);
            if (replay) return new StaffEvidence.Receipt(stored(scope, id), result);
            audit(scope, id, "EVIDENCE", result);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            StaffEvidence saved = new StaffEvidence(evidenceId, result.taskId(), stage, result.version(), id,
                    scope.staffId(), contentType, photo.length, sha256, capturedAt, now, now.plus(settings.retention()));
            proofs.insert(scope.tenant(), saved, key);
            return new StaffEvidence.Receipt(saved, result);
        });
    }
    /** The photos of a task for the manager or the hostess of its venue. */
    public List<StaffEvidence> evidenceForManager(StaffScope scope, String task) {
        return proofs.byTask(scope.tenant(), managed(scope, task).taskId());
    }
    /** The photos of a task for its assignee with an open shift, whoever uploaded them. */
    public List<StaffEvidence> evidenceForAssignee(StaffScope scope, String task) {
        return proofs.byTask(scope.tenant(), assignee(scope, task).taskId());
    }
    public StaffEvidence.File evidenceFileForManager(StaffScope scope, String task, String evidenceId) {
        return file(scope, managed(scope, task), evidenceId);
    }
    public StaffEvidence.File evidenceFileForAssignee(StaffScope scope, String task, String evidenceId) {
        return file(scope, assignee(scope, task), evidenceId);
    }
    private StaffTask managed(StaffScope scope, String task) {
        manager(scope);
        storing();
        return rules.managed(scope, task);
    }
    private StaffTask assignee(StaffScope scope, String task) {
        active(scope);
        storing();
        return rules.assigned(scope, task);
    }
    private StaffEvidence.File file(StaffScope scope, StaffTask task, String evidenceId) {
        var row = proofs.find(scope.tenant(), task.taskId(), evidenceId == null ? "" : evidenceId)
                .orElseThrow(() -> new StaffTaskFailure(404, "NOT_FOUND", "Photo was not found"));
        StaffEvidence found = row.evidence();
        if (!clock.instant().isBefore(found.expiresAt())) throw expired();
        byte[] bytes = evidence.storage().get(row.objectKey(), (int) found.size()).orElseThrow(StaffPortalService::expired);
        // Only the bytes that were recorded at upload are served under this record.
        if (bytes.length != found.size() || !StaffEvidenceStorage.sha256(bytes).equals(found.sha256())) {
            throw StaffEvidenceStorage.unavailable();
        }
        return new StaffEvidence.File(found, bytes);
    }
    private StaffEvidence stored(StaffScope scope, String eventId) {
        return proofs.byEvent(scope.tenant(), eventId).map(JdbcStaffEvidenceRecords.Row::evidence)
                .orElseThrow(() -> new StaffTaskFailure(409, "EVENT_CONFLICT", "This event id was already used for another request"));
    }
    private StaffEvidenceSettings storing() {
        if (evidence == null) throw new StaffTaskFailure(503, "EVIDENCE_UNAVAILABLE", "Photo storage is not connected yet");
        return evidence;
    }
    private static StaffTaskFailure expired() {
        return new StaffTaskFailure(410, "EVIDENCE_EXPIRED", "The photo is no longer kept");
    }
    public void shift(StaffScope scope, String staff, boolean open, String device) {
        bounded(device, 128);
        locked(scope, () -> {
            directoryManager(scope);
            if (jdbc.update("UPDATE astor_staff_members SET shift_open=?,device_id=?,updated_at=CURRENT_TIMESTAMP WHERE tenant=? AND staff_id=? AND active=TRUE",
                    open, open ? device : null, scope.tenant(), staff) != 1) throw new StaffTaskFailure(404, "NOT_FOUND", "Active employee was not found");
            return true;
        });
    }
    public void member(StaffScope scope, String staff, String name, String role, boolean enabled) {
        if (staff == null || staff.isBlank() || name == null || name.isBlank()) throw malformed();
        bounded(staff, 128); bounded(name, 120);
        try { StaffScope.Role.valueOf(role); } catch (Exception e) { throw malformed(); }
        locked(scope, () -> {
            directoryManager(scope);
            // This registers a Keycloak subject; it does not create an identity or grant JWT roles.
            jdbc.update("INSERT INTO astor_staff_members(tenant,staff_id,display_name,role,active) VALUES (?,?,?,?,?) ON CONFLICT (tenant,staff_id) DO UPDATE SET display_name=EXCLUDED.display_name,role=EXCLUDED.role,active=EXCLUDED.active,shift_open=CASE WHEN EXCLUDED.active THEN astor_staff_members.shift_open ELSE FALSE END,updated_at=CURRENT_TIMESTAMP",
                    scope.tenant(), staff, name.trim(), role, enabled);
            return true;
        });
    }
    private <T> T locked(StaffScope scope, Supplier<T> action) {
        return tx.execute(status -> {
            jdbc.update("INSERT INTO astor_staff_tenant_locks(tenant) VALUES (?) ON CONFLICT DO NOTHING", scope.tenant());
            jdbc.queryForObject("SELECT tenant FROM astor_staff_tenant_locks WHERE tenant=? FOR UPDATE", String.class, scope.tenant());
            return action.get();
        });
    }
    /** The venue's directory, for server-side callers that are authorized another way (the glasses relay). */
    public List<Member> members(String tenant) {
        var members = jdbc.query("SELECT staff_id,display_name,role,active,shift_open,device_id FROM astor_staff_members WHERE tenant=? ORDER BY display_name LIMIT 201",
                (rs, n) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5) ? "OPEN" : "CLOSED", rs.getString(6)), tenant);
        if (members.size() > 200) throw new StaffTaskFailure(503, "SNAPSHOT_LIMIT", "Staff directory exceeds the pilot limit; pagination is required");
        return members;
    }
    private boolean onShift(String tenant, String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM astor_staff_members WHERE tenant=? AND staff_id=? AND active=TRUE AND shift_open=TRUE", Boolean.class, tenant, id));
    }
    private void active(StaffScope scope) {
        boolean registered = Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM astor_staff_members WHERE tenant=? AND staff_id=? AND role=? AND active=TRUE",
                Boolean.class, scope.tenant(), scope.staffId(), scope.role().name()));
        if (!registered) throw new StaffTaskFailure(403, "STAFF_INACTIVE", "The staff account is not active");
    }
    private void manager(StaffScope scope) {
        active(scope);
        if (!scope.manages()) throw new StaffTaskFailure(403, "FORBIDDEN", "A manager or hostess is required");
    }
    private void directoryManager(StaffScope scope) {
        active(scope);
        if (scope.role() != StaffScope.Role.MANAGER) throw new StaffTaskFailure(403, "FORBIDDEN", "Only a manager may administer staff and shifts");
    }
    private void bounded(String text, int max) { if (text != null && text.length() > max) throw malformed(); }
    private StaffTaskFailure malformed() { return new StaffTaskFailure(400, "MALFORMED_REQUEST", "Invalid staff request"); }
}
