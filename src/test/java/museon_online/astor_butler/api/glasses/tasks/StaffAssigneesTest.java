package museon_online.astor_butler.api.glasses.tasks;

import museon_online.astor_butler.api.glasses.tasks.StaffPortalService.Member;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StaffAssigneesTest {
    private static Member member(String id, String name, String role, boolean active, String shift) {
        return new Member(id, name, role, active, shift, null);
    }

    private final Member manager = member("manager-1", "Марина Менеджер", "MANAGER", true, "OPEN");
    private final Member anna = member("anna", "Анна Петрова", "WAITER", true, "OPEN");
    private final Member ilya = member("ilya", "Илья", "WAITER", true, "CLOSED");
    private final Member maria = member("maria", "Мария", "HOSTESS", true, "OPEN");
    private final Member gone = member("gone", "Анастасия", "WAITER", false, "OPEN");
    private final List<Member> staff = List.of(manager, anna, ilya, maria, gone);

    @Test void aNameIsFoundAsSpokenInAnyCase() {
        assertThat(StaffAssignees.resolve(staff, "Анна")).isEqualTo(anna);
        assertThat(StaffAssignees.resolve(staff, "Анне")).isEqualTo(anna);
        assertThat(StaffAssignees.resolve(staff, "для Анны")).isEqualTo(anna);
        assertThat(StaffAssignees.resolve(staff, "Петровой")).isEqualTo(anna);
        assertThat(StaffAssignees.resolve(staff, "Марии")).isEqualTo(maria);
        assertThat(StaffAssignees.resolve(staff, "anna")).isEqualTo(anna);
    }

    @Test void aRoleWordFindsTheOnePersonOnShiftWithThatRole() {
        assertThat(StaffAssignees.resolve(staff, "официанту")).isEqualTo(anna);
        assertThat(StaffAssignees.resolve(staff, "хостес")).isEqualTo(maria);
        assertThat(StaffAssignees.resolve(staff, "менеджеру")).isEqualTo(manager);
    }

    @Test void nobodyOffShiftInactiveUnknownOrAmbiguousIsChosen() {
        assertThat(StaffAssignees.resolve(staff, "Илье")).as("closed shift").isNull();
        assertThat(StaffAssignees.resolve(staff, "Анастасии")).as("inactive").isNull();
        assertThat(StaffAssignees.resolve(staff, "Сергею")).isNull();
        assertThat(StaffAssignees.resolve(staff, null)).isNull();
        assertThat(StaffAssignees.resolve(staff, "  ")).isNull();
        assertThat(StaffAssignees.resolve(List.of(), "Анне")).isNull();
        // Two waiters on shift: a role word points at both, so at nobody.
        var two = List.of(anna, member("ilya", "Илья", "WAITER", true, "OPEN"));
        assertThat(StaffAssignees.resolve(two, "официанту")).isNull();
        // Two people with the same first name are not guessed between; a surname settles it.
        var twins = List.of(member("anna-p", "Анна Петрова", "WAITER", true, "OPEN"), member("anna-s", "Анна Сидорова", "WAITER", true, "OPEN"));
        assertThat(StaffAssignees.resolve(twins, "Анне")).isNull();
        assertThat(StaffAssignees.resolve(twins, "Анне Сидоровой")).isEqualTo(twins.get(1));
        // «Марии» is Мария, not Марина: a consonant in the ending is another name.
        var similar = List.of(member("maria", "Мария", "WAITER", true, "OPEN"), member("marina", "Марина", "WAITER", true, "OPEN"));
        assertThat(StaffAssignees.resolve(similar, "Марии")).isEqualTo(similar.get(0));
        assertThat(StaffAssignees.resolve(similar, "Марине")).isEqualTo(similar.get(1));
    }

    @Test void anExactNameBeatsAStemAndARoleWord() {
        var both = List.of(member("anna", "Анна", "WAITER", true, "OPEN"), member("ann", "Аннушка", "WAITER", true, "OPEN"));
        assertThat(StaffAssignees.resolve(both, "официанту Анне")).isEqualTo(both.get(0));
        assertThat(StaffAssignees.resolve(both, "Аннушке")).isEqualTo(both.get(1));
    }

    /* ---------- with the shift schedule (StaffRoster) ---------- */

    private final Member ilyaOpen = member("ilya", "Илья", "WAITER", true, "OPEN");
    private final List<Member> twoWaiters = List.of(manager, anna, ilyaOpen, maria);

    @Test void aRoleWordFindsTheOnePersonTheScheduleHasOnShift() {
        // Both left a shift open; the schedule has only Илья on now.
        var scheduled = Map.of(StaffScope.Role.WAITER, Set.of("ilya"));
        assertThat(StaffAssignees.resolve(twoWaiters, "официанту")).as("without the schedule: two, so nobody").isNull();
        assertThat(StaffAssignees.resolve(twoWaiters, "официанту", scheduled)).isEqualTo(ilyaOpen);
        // A name still wins over the schedule: the speaker meant that person.
        assertThat(StaffAssignees.resolve(twoWaiters, "Анне", scheduled)).isEqualTo(anna);
        // Another role is matched by the directory as before.
        assertThat(StaffAssignees.resolve(twoWaiters, "хостес", scheduled)).isEqualTo(maria);
    }

    @Test void theScheduleNeverGuessesBetweenTwoAndNeverPassesAClosedShift() {
        assertThat(StaffAssignees.resolve(twoWaiters, "официанту", Map.of(StaffScope.Role.WAITER, Set.of("anna", "ilya"))))
                .as("two on shift by the schedule").isNull();
        // Scheduled, but no open shift: a task cannot go there, and the open one is not who the schedule names.
        assertThat(StaffAssignees.resolve(staff, "официанту", Map.of(StaffScope.Role.WAITER, Set.of("ilya")))).isNull();
        // Scheduled, but not in the directory or inactive: nobody.
        assertThat(StaffAssignees.resolve(twoWaiters, "официанту", Map.of(StaffScope.Role.WAITER, Set.of("sergey")))).isNull();
        assertThat(StaffAssignees.resolve(staff, "официанту", Map.of(StaffScope.Role.WAITER, Set.of("gone")))).isNull();
    }

    @Test void theScheduleSaysWhoWorksTheRoleToday() {
        // The manager stands at the door today: «хостес» is her, the directory's hostess is not on the schedule.
        var scheduled = Map.of(StaffScope.Role.HOSTESS, Set.of("manager-1"));
        assertThat(StaffAssignees.resolve(staff, "хостес", scheduled)).isEqualTo(manager);
    }

    @Test void anEmptyScheduleChangesNothing() {
        for (Map<StaffScope.Role, Set<String>> empty : List.<Map<StaffScope.Role, Set<String>>>of(
                Map.of(), Map.of(StaffScope.Role.WAITER, Set.of()))) {
            assertThat(StaffAssignees.resolve(staff, "официанту", empty)).isEqualTo(anna);
            assertThat(StaffAssignees.resolve(staff, "хостес", empty)).isEqualTo(maria);
            assertThat(StaffAssignees.resolve(twoWaiters, "официанту", empty)).isNull();
        }
        assertThat(StaffAssignees.resolve(staff, "официанту", null)).isEqualTo(anna);
    }
}
