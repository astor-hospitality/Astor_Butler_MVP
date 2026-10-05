package museon_online.astor_butler.api.glasses.tasks;

import museon_online.astor_butler.api.glasses.tasks.StaffTaskService.Command;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskService.Delivery;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskService.Draft;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskService.StageDraft;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffTaskServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private static final StaffScope HOSTESS = new StaffScope("AERIS", "maria", StaffScope.Role.HOSTESS);
    private static final StaffScope ANNA = new StaffScope("AERIS", "anna", StaffScope.Role.WAITER);
    private static final StaffScope ILYA = new StaffScope("AERIS", "ilya", StaffScope.Role.WAITER);
    private static final StaffScope STRANGER = new StaffScope("OTHER", "anna", StaffScope.Role.WAITER);

    private final Set<String> onShift = new HashSet<>(Set.of("AERIS/maria", "AERIS/anna", "AERIS/ilya", "OTHER/anna"));
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger events = new AtomicInteger();
    private final StaffTaskService service = new StaffTaskService(new InMemoryStaffTaskStore(),
            (tenant, staffId) -> onShift.contains(tenant + "/" + staffId),
            Clock.fixed(NOW, ZoneOffset.UTC), () -> "task-" + ids.incrementAndGet());

    private String event() {
        return "event-" + events.incrementAndGet();
    }

    private StaffTask table(String tableCode, String staffId) {
        return service.create(HOSTESS, event(), new Draft("Concierge", tableCode, "Подготовить стол на двоих",
                "Подготовить стол на двоих, проверить сервировку.",
                List.of(new StageDraft("seats", "Стулья и посадка", false), new StageDraft("setting", "Сервировка", true)),
                StaffTask.Priority.NORMAL, null, staffId));
    }

    private static void refused(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StaffTaskFailure.class, failure -> {
            assertThat(failure.status).isEqualTo(status);
            assertThat(failure.code).isEqualTo(code);
        });
    }

    @Test void wholeTaskGoesFromAssignedToDoneWithVersionsAndDeliveryMarks() {
        StaffTask task = table("13", "anna");
        assertThat(task.status()).isEqualTo(StaffTask.Status.ASSIGNED);
        assertThat(task.version()).isEqualTo(1);

        task = service.delivery(ANNA, task.taskId(), event(), Delivery.VOICED);
        assertThat(task.deliveredAt()).isEqualTo(NOW);
        assertThat(task.voicedAt()).isEqualTo(NOW);
        assertThat(task.version()).as("delivery marks do not change the version").isEqualTo(1);

        task = service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 1, null);
        assertThat(task.status()).isEqualTo(StaffTask.Status.ACCEPTED);
        task = service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 2, "seats");
        assertThat(task.status()).isEqualTo(StaffTask.Status.IN_PROGRESS);
        task = service.evidence(ANNA, task.taskId(), event(), "setting", NOW);
        task = service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 4, null);
        task = service.command(ANNA, task.taskId(), event(), Command.COMPLETE, 5, null);

        assertThat(task.status()).isEqualTo(StaffTask.Status.DONE);
        assertThat(task.version()).isEqualTo(6);
        assertThat(task.stages()).allMatch(StaffTask.Stage::done);
    }

    @Test void photoGoesToItsOwnTaskAndStageWhenOnePersonHasTwoTables() {
        StaffTask first = table("13", "anna");
        StaffTask second = table("7", "anna");
        service.command(ANNA, first.taskId(), event(), Command.ACCEPT, 1, null);
        service.command(ANNA, second.taskId(), event(), Command.ACCEPT, 1, null);

        StaffTask changed = service.evidence(ANNA, second.taskId(), event(), "setting", NOW);

        assertThat(changed.tableCode()).isEqualTo("7");
        assertThat(changed.stages()).extracting(StaffTask.Stage::evidence).containsExactly(0, 1);
        StaffTask untouched = service.list(HOSTESS).stream().filter(task -> task.taskId().equals(first.taskId())).findFirst().orElseThrow();
        assertThat(untouched.stages()).extracting(StaffTask.Stage::evidence).containsExactly(0, 0);
        assertThat(untouched.version()).isEqualTo(2);
    }

    @Test void otherVenueSeesNothingAndOtherStaffIsForbidden() {
        StaffTask task = table("13", "anna");

        refused(() -> service.command(STRANGER, task.taskId(), event(), Command.ACCEPT, 1, null), 404, "NOT_FOUND");
        refused(() -> service.command(STRANGER, "no-such-task", event(), Command.ACCEPT, 1, null), 404, "NOT_FOUND");
        refused(() -> service.command(ILYA, task.taskId(), event(), Command.ACCEPT, 1, null), 403, "FORBIDDEN");
        refused(() -> service.evidence(ILYA, task.taskId(), event(), "setting", NOW), 403, "FORBIDDEN");
        refused(() -> service.list(ANNA), 403, "FORBIDDEN");
        refused(() -> service.cancel(ANNA, task.taskId(), event(), 1), 403, "FORBIDDEN");

        assertThat(service.feed(ILYA, 0, 20).items()).isEmpty();
        assertThat(service.feed(STRANGER, 0, 20).items()).isEmpty();
    }

    @Test void repeatedEventDoesNotActTwice() {
        StaffTask task = table("13", "anna");
        String accept = event();
        StaffTask once = service.command(ANNA, task.taskId(), accept, Command.ACCEPT, 1, null);
        StaffTask again = service.command(ANNA, task.taskId(), accept, Command.ACCEPT, 1, null);
        assertThat(again).isEqualTo(once);
        assertThat(again.version()).isEqualTo(2);

        String photo = event();
        service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 2, null);
        service.evidence(ANNA, task.taskId(), photo, "setting", NOW);
        StaffTask repeated = service.evidence(ANNA, task.taskId(), photo, "setting", NOW);
        assertThat(repeated.stages().get(1).evidence()).isEqualTo(1);

        refused(() -> service.command(ANNA, task.taskId(), accept, Command.COMPLETE, 1, null), 409, "EVENT_CONFLICT");
        refused(() -> service.command(ANNA, task.taskId(), " ", Command.ACCEPT, 1, null), 400, "MALFORMED_REQUEST");
    }

    @Test void staleVersionIsAConflict() {
        StaffTask task = table("13", "anna");
        service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 1, null);

        refused(() -> service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 1, null), 409, "VERSION_CONFLICT");
        refused(() -> service.cancel(HOSTESS, task.taskId(), event(), 1), 409, "VERSION_CONFLICT");
    }

    @Test void closedShiftGivesEmptyFeedAndRefusesCommands() {
        StaffTask task = table("13", "anna");
        assertThat(service.feed(ANNA, 0, 20).items()).hasSize(1);

        onShift.remove("AERIS/anna");

        var page = service.feed(ANNA, 0, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).as("cursor must not move while the shift is closed").isZero();
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 1, null), 403, "SHIFT_CLOSED");
        refused(() -> table("5", "anna"), 409, "ASSIGNEE_OFF_SHIFT");
    }

    @Test void stageNeedsItsPhotoAndTaskNeedsEveryStage() {
        StaffTask task = table("13", "anna");
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 1, null), 409, "INVALID_TRANSITION");
        refused(() -> service.evidence(ANNA, task.taskId(), event(), "setting", NOW), 409, "INVALID_TRANSITION");

        service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 1, null);
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 2, "setting"), 409, "STAGE_OUT_OF_ORDER");
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 2, "unknown"), 400, "MALFORMED_REQUEST");
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.COMPLETE, 2, null), 409, "STAGES_OPEN");

        service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 2, "seats");
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 3, "setting"), 409, "EVIDENCE_REQUIRED");
        refused(() -> service.evidence(ANNA, task.taskId(), event(), "seats", NOW), 409, "STAGE_CLOSED");
    }

    @Test void helpPausesWorkUntilManagerResolvesIt() {
        StaffTask task = table("2", "ilya");
        service.command(ILYA, task.taskId(), event(), Command.ACCEPT, 1, null);
        StaffTask asking = service.command(ILYA, task.taskId(), event(), Command.HELP, 2, null);
        assertThat(asking.status()).isEqualTo(StaffTask.Status.HELP_REQUESTED);

        refused(() -> service.command(ILYA, task.taskId(), event(), Command.STAGE_DONE, 3, null), 409, "INVALID_TRANSITION");
        refused(() -> service.resolveHelp(ILYA, task.taskId(), event(), 3), 403, "FORBIDDEN");

        StaffTask resumed = service.resolveHelp(HOSTESS, task.taskId(), event(), 3);
        assertThat(resumed.status()).isEqualTo(StaffTask.Status.IN_PROGRESS);
        refused(() -> service.resolveHelp(HOSTESS, task.taskId(), event(), 4), 409, "INVALID_TRANSITION");
    }

    @Test void closedTaskNeverChanges() {
        StaffTask task = table("13", "anna");
        StaffTask cancelled = service.cancel(HOSTESS, task.taskId(), event(), 1);
        assertThat(cancelled.status()).isEqualTo(StaffTask.Status.CANCELLED);

        refused(() -> service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 2, null), 409, "TASK_CLOSED");
        refused(() -> service.reassign(HOSTESS, task.taskId(), event(), 2, "ilya"), 409, "TASK_CLOSED");
        refused(() -> service.cancel(HOSTESS, task.taskId(), event(), 2), 409, "TASK_CLOSED");
    }

    @Test void reassignMovesTaskToTheOtherFeedAndKeepsProgress() {
        StaffTask task = table("13", "anna");
        service.delivery(ANNA, task.taskId(), event(), Delivery.DELIVERED);
        service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 1, null);
        service.command(ANNA, task.taskId(), event(), Command.STAGE_DONE, 2, null);

        refused(() -> service.reassign(HOSTESS, task.taskId(), event(), 3, "anna"), 400, "MALFORMED_REQUEST");
        refused(() -> service.reassign(HOSTESS, task.taskId(), event(), 3, "oleg"), 409, "ASSIGNEE_OFF_SHIFT");
        StaffTask moved = service.reassign(HOSTESS, task.taskId(), event(), 3, "ilya");

        assertThat(moved.assigneeStaffId()).isEqualTo("ilya");
        assertThat(moved.status()).isEqualTo(StaffTask.Status.ASSIGNED);
        assertThat(moved.deliveredAt()).isNull();
        assertThat(moved.stages().get(0).done()).isTrue();
        assertThat(service.feed(ILYA, 0, 20).items()).extracting(StaffTask::taskId).containsExactly(task.taskId());
        assertThat(service.feed(ANNA, 0, 20).items()).isEmpty();
        refused(() -> service.command(ANNA, task.taskId(), event(), Command.COMPLETE, 4, null), 403, "FORBIDDEN");
    }

    @Test void feedReturnsOnlyWhatChangedAfterTheCursor() {
        StaffTask first = table("13", "anna");
        StaffTask second = table("7", "anna");

        var page = service.feed(ANNA, 0, 1);
        assertThat(page.items()).extracting(StaffTask::taskId).containsExactly(first.taskId());
        var rest = service.feed(ANNA, page.nextCursor(), 20);
        assertThat(rest.items()).extracting(StaffTask::taskId).containsExactly(second.taskId());
        assertThat(service.feed(ANNA, rest.nextCursor(), 20).items()).isEmpty();

        service.delivery(ANNA, first.taskId(), event(), Delivery.DELIVERED);
        var afterDelivery = service.feed(ANNA, rest.nextCursor(), 20);
        assertThat(afterDelivery.items()).extracting(StaffTask::taskId).containsExactly(first.taskId());
        service.delivery(ANNA, first.taskId(), event(), Delivery.DELIVERED);
        assertThat(service.feed(ANNA, afterDelivery.nextCursor(), 20).items())
                .as("a repeated delivery mark changes nothing and must not resend the task").isEmpty();
    }

    @Test void acceptCountsAsDeliveredWhenTheDeliveryMarkWasLost() {
        StaffTask task = table("13", "anna");
        StaffTask accepted = service.command(ANNA, task.taskId(), event(), Command.ACCEPT, 1, null);
        assertThat(accepted.deliveredAt()).isEqualTo(NOW);
        assertThat(accepted.voicedAt()).isNull();
    }

    @Test void managerCannotCreateBrokenTasks() {
        refused(() -> service.create(HOSTESS, event(), new Draft("", " ", "Стол", "", List.of(new StageDraft("a", "Шаг", false)),
                null, null, "anna")), 400, "MALFORMED_REQUEST");
        refused(() -> service.create(HOSTESS, event(), new Draft("", "5", "Стол", "", List.of(), null, null, "anna")),
                400, "MALFORMED_REQUEST");
        refused(() -> service.create(HOSTESS, event(), new Draft("", "5", "Стол", "",
                List.of(new StageDraft("a", "Шаг", false), new StageDraft("a", "Ещё шаг", false)), null, null, "anna")),
                400, "MALFORMED_REQUEST");
        refused(() -> service.create(ANNA, event(), new Draft("", "5", "Стол", "", List.of(new StageDraft("a", "Шаг", false)),
                null, null, "anna")), 403, "FORBIDDEN");
        assertThat(service.list(HOSTESS)).isEmpty();
    }
}
