package museon_online.astor_butler.api.glasses.tasks;

import museon_online.astor_butler.api.glasses.tasks.StaffPortalService.Member;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
