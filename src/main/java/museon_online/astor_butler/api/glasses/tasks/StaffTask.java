package museon_online.astor_butler.api.glasses.tasks;

import java.time.Instant;
import java.util.List;

/** One assignment for one staff member. Immutable: every change produces a new value with the next version. */
public record StaffTask(String taskId, String tenant, String sourceRef, String tableCode, String title,
                        String instruction, List<Stage> stages, Priority priority, Instant deadline,
                        String assigneeStaffId, Status status, long version, Instant deliveredAt, Instant voicedAt) {

    public enum Priority { NORMAL, HIGH }

    public enum Status {
        ASSIGNED, ACCEPTED, IN_PROGRESS, HELP_REQUESTED, DONE, CANCELLED;

        public boolean closed() { return this == DONE || this == CANCELLED; }

        boolean working() { return this == ACCEPTED || this == IN_PROGRESS; }
    }

    /** A step of the task. A received photo is counted here; it does not close the step by itself. */
    public record Stage(String code, String title, boolean evidenceRequired, boolean done, int evidence) { }

    public StaffTask {
        stages = List.copyOf(stages);
    }

    /** The first step that is not done yet, or null when every step is done. */
    Stage currentStage() {
        return stages.stream().filter(stage -> !stage.done()).findFirst().orElse(null);
    }

    StaffTask withStatus(Status next) {
        return new StaffTask(taskId, tenant, sourceRef, tableCode, title, instruction, stages, priority, deadline,
                assigneeStaffId, next, version, deliveredAt, voicedAt);
    }

    StaffTask withStages(List<Stage> next) {
        return new StaffTask(taskId, tenant, sourceRef, tableCode, title, instruction, next, priority, deadline,
                assigneeStaffId, status, version, deliveredAt, voicedAt);
    }

    StaffTask withDelivery(Instant delivered, Instant voiced) {
        return new StaffTask(taskId, tenant, sourceRef, tableCode, title, instruction, stages, priority, deadline,
                assigneeStaffId, status, version, delivered, voiced);
    }

    StaffTask withAssignee(String staffId) {
        return new StaffTask(taskId, tenant, sourceRef, tableCode, title, instruction, stages, priority, deadline,
                staffId, status, version, deliveredAt, voicedAt);
    }

    StaffTask nextVersion() {
        return new StaffTask(taskId, tenant, sourceRef, tableCode, title, instruction, stages, priority, deadline,
                assigneeStaffId, status, version + 1, deliveredAt, voicedAt);
    }
}
