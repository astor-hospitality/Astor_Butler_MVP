package museon_online.astor_butler.api.glasses.tasks;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Rules for staff tasks: who may see and change a task, which transitions are allowed, how retries and
 * concurrent changes are handled. A voice model may recognise "the table is ready", but only these rules
 * change a task. No transport, storage or identity provider is assumed here.
 *
 * Methods are synchronized: correct for one JVM. With several instances the store's compare-and-set on
 * the version is what protects a task; processed events then need a unique key in the database.
 */
public final class StaffTaskService {

    public enum Command { ACCEPT, STAGE_DONE, COMPLETE, HELP }

    public enum Delivery { DELIVERED, VOICED }

    public record StageDraft(String code, String title, boolean evidenceRequired) { }

    public record Draft(String sourceRef, String tableCode, String title, String instruction, List<StageDraft> stages,
                        StaffTask.Priority priority, Instant deadline, String assigneeStaffId) { }

    private static final int MAX_STAGES = 10;
    private static final int MAX_PAGE = 100;

    private final StaffTaskStore store;
    private final StaffShifts shifts;
    private final Clock clock;
    private final Supplier<String> taskIds;

    public StaffTaskService(StaffTaskStore store, StaffShifts shifts, Clock clock) {
        this(store, shifts, clock, () -> UUID.randomUUID().toString());
    }

    StaffTaskService(StaffTaskStore store, StaffShifts shifts, Clock clock, Supplier<String> taskIds) {
        this.store = store;
        this.shifts = shifts;
        this.clock = clock;
        this.taskIds = taskIds;
    }

    /* ---------- Staff member ---------- */

    /** Only tasks assigned to the caller. Without an open shift the feed is empty and the cursor does not move. */
    public synchronized StaffTaskStore.Page feed(StaffScope scope, long cursor, int limit) {
        if (!shifts.open(scope.tenant(), scope.staffId())) return new StaffTaskStore.Page(List.of(), cursor);
        return store.changedFor(scope.tenant(), scope.staffId(), Math.max(0, cursor), Math.max(1, Math.min(limit, MAX_PAGE)));
    }

    /** Delivery is tracked apart from the status: delivered or voiced does not mean accepted. */
    public synchronized StaffTask delivery(StaffScope scope, String taskId, String eventId, Delivery kind) {
        return once(scope, eventId, fingerprint("delivery", scope.staffId(), taskId, kind), () -> {
            StaffTask task = own(scope, taskId);
            Instant now = clock.instant();
            Instant delivered = task.deliveredAt() == null ? now : task.deliveredAt();
            Instant voiced = kind == Delivery.VOICED && task.voicedAt() == null ? now : task.voicedAt();
            StaffTask next = task.withDelivery(delivered, voiced);
            // Nothing new to record: no write, so the task does not come back in the feed as "changed".
            if (next.equals(task)) return task;
            // Same version on purpose: delivery marks must not invalidate a command the phone is about to send.
            if (!store.replace(next, task.version())) throw versionConflict();
            return next;
        });
    }

    public synchronized StaffTask command(StaffScope scope, String taskId, String eventId, Command type,
                                          long expectedVersion, String stageCode) {
        if (type == null) throw malformed("Command type is required");
        return once(scope, eventId, fingerprint("command", scope.staffId(), taskId, type, expectedVersion, stageCode), () -> {
            StaffTask task = open(own(scope, taskId), expectedVersion);
            return save(switch (type) {
                case ACCEPT -> accept(task);
                case STAGE_DONE -> stageDone(task, stageCode);
                case COMPLETE -> complete(task);
                case HELP -> working(task).withStatus(StaffTask.Status.HELP_REQUESTED);
            }, task.version());
        });
    }

    /** A received photo is counted on its stage. It closes neither the stage nor the task. */
    public synchronized StaffTask evidence(StaffScope scope, String taskId, String eventId, String stageCode,
                                           Instant capturedAt) {
        return once(scope, eventId, fingerprint("evidence", scope.staffId(), taskId, stageCode, capturedAt), () -> {
            StaffTask task = working(notClosed(own(scope, taskId)));
            StaffTask.Stage stage = stage(task, stageCode);
            if (stage.done()) throw conflict("STAGE_CLOSED", "The stage is already closed");
            return save(replaceStage(task, new StaffTask.Stage(stage.code(), stage.title(), stage.evidenceRequired(),
                    false, stage.evidence() + 1)), task.version());
        });
    }

    /* ---------- Hostess or manager ---------- */

    public synchronized List<StaffTask> list(StaffScope scope) {
        manager(scope);
        return store.byTenant(scope.tenant());
    }

    public synchronized StaffTask create(StaffScope scope, String eventId, Draft draft) {
        manager(scope);
        if (draft == null) throw malformed("Task is required");
        return once(scope, eventId, fingerprint("create", scope.staffId(), draft), () -> {
            List<StaffTask.Stage> stages = stages(draft);
            if (blank(draft.tableCode()) || blank(draft.title())) throw malformed("Table and title are required");
            onShift(scope.tenant(), draft.assigneeStaffId());
            StaffTask task = new StaffTask(taskIds.get(), scope.tenant(), text(draft.sourceRef()), draft.tableCode().trim(),
                    draft.title().trim(), text(draft.instruction()), stages,
                    draft.priority() == null ? StaffTask.Priority.NORMAL : draft.priority(), draft.deadline(),
                    draft.assigneeStaffId(), StaffTask.Status.ASSIGNED, 1, null, null);
            store.insert(task);
            return task;
        });
    }

    /** The task goes back to ASSIGNED for the new person; finished stages and photos stay. */
    public synchronized StaffTask reassign(StaffScope scope, String taskId, String eventId, long expectedVersion,
                                           String staffId) {
        manager(scope);
        return once(scope, eventId, fingerprint("reassign", scope.staffId(), taskId, expectedVersion, staffId), () -> {
            StaffTask task = open(inTenant(scope, taskId), expectedVersion);
            if (blank(staffId) || staffId.equals(task.assigneeStaffId())) throw malformed("Choose another staff member");
            onShift(scope.tenant(), staffId);
            return save(task.withAssignee(staffId).withStatus(StaffTask.Status.ASSIGNED).withDelivery(null, null),
                    task.version());
        });
    }

    public synchronized StaffTask resolveHelp(StaffScope scope, String taskId, String eventId, long expectedVersion) {
        manager(scope);
        return once(scope, eventId, fingerprint("resolve-help", scope.staffId(), taskId, expectedVersion), () -> {
            StaffTask task = open(inTenant(scope, taskId), expectedVersion);
            if (task.status() != StaffTask.Status.HELP_REQUESTED) throw conflict("INVALID_TRANSITION", "Help was not requested");
            return save(task.withStatus(StaffTask.Status.IN_PROGRESS), task.version());
        });
    }

    public synchronized StaffTask cancel(StaffScope scope, String taskId, String eventId, long expectedVersion) {
        manager(scope);
        return once(scope, eventId, fingerprint("cancel", scope.staffId(), taskId, expectedVersion), () -> {
            StaffTask task = open(inTenant(scope, taskId), expectedVersion);
            return save(task.withStatus(StaffTask.Status.CANCELLED), task.version());
        });
    }

    /* ---------- Transitions ---------- */

    private StaffTask accept(StaffTask task) {
        if (task.status() != StaffTask.Status.ASSIGNED) throw conflict("INVALID_TRANSITION", "Only an assigned task can be accepted");
        // Accepting proves the task reached the person, even if the delivery mark was lost on the way.
        Instant delivered = task.deliveredAt() == null ? clock.instant() : task.deliveredAt();
        return task.withStatus(StaffTask.Status.ACCEPTED).withDelivery(delivered, task.voicedAt());
    }

    private StaffTask stageDone(StaffTask task, String stageCode) {
        StaffTask.Stage current = working(task).currentStage();
        if (current == null) throw conflict("STAGE_CLOSED", "Every stage is already closed");
        if (stageCode != null && !stage(task, stageCode).code().equals(current.code())) {
            throw conflict("STAGE_OUT_OF_ORDER", "Stages are closed in their order");
        }
        if (current.evidenceRequired() && current.evidence() == 0) {
            throw conflict("EVIDENCE_REQUIRED", "A photo is required before this stage can be closed");
        }
        return replaceStage(task, new StaffTask.Stage(current.code(), current.title(), current.evidenceRequired(),
                true, current.evidence())).withStatus(StaffTask.Status.IN_PROGRESS);
    }

    private StaffTask complete(StaffTask task) {
        if (working(task).currentStage() != null) throw conflict("STAGES_OPEN", "Some stages are not closed yet");
        return task.withStatus(StaffTask.Status.DONE);
    }

    /* ---------- Access ---------- */

    /** A task of another venue is reported exactly like a missing one, so its existence does not leak. */
    private StaffTask inTenant(StaffScope scope, String taskId) {
        return store.find(taskId == null ? "" : taskId)
                .filter(task -> task.tenant().equals(scope.tenant()))
                .orElseThrow(() -> new StaffTaskFailure(404, "NOT_FOUND", "Task was not found"));
    }

    private StaffTask own(StaffScope scope, String taskId) {
        StaffTask task = inTenant(scope, taskId);
        if (!task.assigneeStaffId().equals(scope.staffId())) {
            throw new StaffTaskFailure(403, "FORBIDDEN", "The task is assigned to someone else");
        }
        if (!shifts.open(scope.tenant(), scope.staffId())) {
            throw new StaffTaskFailure(403, "SHIFT_CLOSED", "The shift is not open");
        }
        return task;
    }

    private void manager(StaffScope scope) {
        if (!scope.manages()) throw new StaffTaskFailure(403, "FORBIDDEN", "Only a hostess or a manager may do this");
    }

    private void onShift(String tenant, String staffId) {
        if (blank(staffId)) throw malformed("Assignee is required");
        if (!shifts.open(tenant, staffId)) throw conflict("ASSIGNEE_OFF_SHIFT", "The staff member has no open shift");
    }

    /* ---------- Guards ---------- */

    private StaffTask notClosed(StaffTask task) {
        if (task.status().closed()) throw conflict("TASK_CLOSED", "The task is already closed");
        return task;
    }

    private StaffTask open(StaffTask task, long expectedVersion) {
        if (notClosed(task).version() != expectedVersion) throw versionConflict();
        return task;
    }

    private StaffTask working(StaffTask task) {
        if (!task.status().working()) throw conflict("INVALID_TRANSITION", "The task is not being worked on");
        return task;
    }

    private StaffTask.Stage stage(StaffTask task, String code) {
        return task.stages().stream().filter(stage -> stage.code().equals(code)).findFirst()
                .orElseThrow(() -> malformed("Unknown stage"));
    }

    private StaffTask replaceStage(StaffTask task, StaffTask.Stage changed) {
        return task.withStages(task.stages().stream()
                .map(stage -> stage.code().equals(changed.code()) ? changed : stage).toList());
    }

    private List<StaffTask.Stage> stages(Draft draft) {
        List<StageDraft> drafts = draft.stages() == null ? List.of() : draft.stages();
        if (drafts.isEmpty() || drafts.size() > MAX_STAGES) throw malformed("A task needs from 1 to " + MAX_STAGES + " stages");
        Set<String> codes = new HashSet<>();
        List<StaffTask.Stage> stages = new ArrayList<>();
        for (StageDraft stage : drafts) {
            if (stage == null || blank(stage.code()) || blank(stage.title()) || !codes.add(stage.code())) {
                throw malformed("Every stage needs a unique code and a title");
            }
            stages.add(new StaffTask.Stage(stage.code(), stage.title().trim(), stage.evidenceRequired(), false, 0));
        }
        return stages;
    }

    /* ---------- Retries and versions ---------- */

    /**
     * Runs the action once per event id. A retry with the same id and the same request returns the first
     * result without acting again; the same id with a different request is a conflict.
     */
    private StaffTask once(StaffScope scope, String eventId, String fingerprint, Supplier<StaffTask> action) {
        if (blank(eventId) || eventId.length() > 64) throw malformed("Event id is required");
        var seen = store.event(scope.tenant(), eventId);
        if (seen.isPresent()) {
            if (!seen.get().fingerprint().equals(fingerprint)) {
                throw conflict("EVENT_CONFLICT", "This event id was already used for another request");
            }
            return seen.get().result();
        }
        StaffTask result = action.get();
        store.remember(new StaffTaskStore.ProcessedEvent(scope.tenant(), eventId, fingerprint, result));
        return result;
    }

    private StaffTask save(StaffTask changed, long expectedVersion) {
        StaffTask next = changed.nextVersion();
        if (!store.replace(next, expectedVersion)) throw versionConflict();
        return next;
    }

    private static String fingerprint(Object... parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) text.append(part).append('\u001f');
        return text.toString();
    }

    private static StaffTaskFailure versionConflict() {
        return conflict("VERSION_CONFLICT", "The task was changed, read it again");
    }

    private static StaffTaskFailure conflict(String code, String message) {
        return new StaffTaskFailure(409, code, message);
    }

    private static StaffTaskFailure malformed(String message) {
        return new StaffTaskFailure(400, "MALFORMED_REQUEST", message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
