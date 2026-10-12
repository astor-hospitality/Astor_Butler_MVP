package museon_online.astor_butler.domain.tips;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The migration 2026-10-12-tips-personal and the directory/assignment queries, on the SQL production runs. */
class TipsPersonalRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    private TipsPersonalTestDatabase database;
    private JdbcTemplate jdbc;
    private TipsPersonalRepository repository;

    @BeforeEach
    void directory() {
        database = TipsPersonalTestDatabase.create();
        jdbc = database.jdbc();
        repository = new TipsPersonalRepository(jdbc);
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('AERIS', 'daniil', 'Даниил', 'WAITER', TRUE)");
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('AERIS', 'anna', 'Анна', 'WAITER', TRUE)");
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('AERIS', 'gone', 'Ушёл', 'WAITER', FALSE)");
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('AERIS', 'dasha', 'Даша', 'MANAGER', TRUE)");
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('OTHER', 'daniil', 'Другой Даниил', 'WAITER', TRUE)");
    }

    @AfterEach
    void drop() throws Exception {
        database.close();
    }

    @Test
    void theMigrationAddsTheLinkColumnsWithoutALinkForAnyone() {
        List<TipStaffMember> members = repository.activeMembers("AERIS");

        assertThat(members).extracting(TipStaffMember::staffId).containsExactly("anna", "daniil", "dasha");
        assertThat(members).noneMatch(TipStaffMember::hasTipLink);
        assertThat(members).extracting(TipStaffMember::tipNote).containsOnlyNulls();
    }

    @Test
    void theLinkIsKeptPerTenantAndClearedWithNull() {
        assertThat(repository.setTipLink("AERIS", "daniil", "https://example.test/tip/daniil", "СБП, Сбер")).isTrue();

        TipStaffMember daniil = repository.member("AERIS", "daniil").orElseThrow();
        assertThat(daniil.tipLink()).isEqualTo("https://example.test/tip/daniil");
        assertThat(daniil.tipNote()).isEqualTo("СБП, Сбер");
        assertThat(repository.member("OTHER", "daniil").orElseThrow().hasTipLink()).isFalse();

        assertThat(repository.setTipLink("AERIS", "daniil", null, null)).isTrue();
        assertThat(repository.member("AERIS", "daniil").orElseThrow().hasTipLink()).isFalse();
        assertThat(repository.setTipLink("AERIS", "nobody", "https://example.test/x", null)).isFalse();
    }

    @Test
    void aSecondAssignmentOfTheSameTableAndDayReplacesTheFirst() {
        repository.assign("AERIS", "5", DAY, "daniil", "Даша (@dasha)");
        repository.assign("AERIS", "5", DAY, "anna", "Даша (@dasha)");
        repository.assign("AERIS", "5", DAY.plusDays(1), "daniil", "Даша (@dasha)");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM table_waiter_assignments", Integer.class)).isEqualTo(2);
        assertThat(repository.waiterOfTable("AERIS", "5", DAY)).map(TipStaffMember::staffId).contains("anna");
        assertThat(repository.waiterOfTable("AERIS", "5", DAY.plusDays(1))).map(TipStaffMember::staffId).contains("daniil");
        assertThat(repository.waiterOfTable("AERIS", "6", DAY)).isEmpty();
        assertThat(repository.waiterOfTable("OTHER", "5", DAY)).isEmpty();
    }

    @Test
    void theWaiterOfATableComesWithTheirLinkAndAnInactiveOneIsNobody() {
        repository.setTipLink("AERIS", "daniil", "https://example.test/tip/daniil", null);
        repository.assign("AERIS", "3", DAY, "daniil", null);
        repository.assign("AERIS", "4", DAY, "gone", null);

        assertThat(repository.waiterOfTable("AERIS", "3", DAY)).map(TipStaffMember::tipLink).contains("https://example.test/tip/daniil");
        assertThat(repository.waiterOfTable("AERIS", "4", DAY)).isEmpty();
    }

    @Test
    void theDaysAssignmentsListTablesInNaturalOrderAndCanBeRemoved() {
        repository.assign("AERIS", "10", DAY, "anna", "Даша");
        repository.assign("AERIS", "2", DAY, "daniil", "Даша");
        repository.assign("AERIS", "1", DAY, "daniil", "Даша");

        List<TableWaiterAssignment> assignments = repository.assignments("AERIS", DAY);
        assertThat(assignments).extracting(TableWaiterAssignment::tableCode).containsExactly("1", "2", "10");
        assertThat(assignments).extracting(TableWaiterAssignment::displayName).containsExactly("Даниил", "Даниил", "Анна");
        assertThat(assignments.get(0).shiftDate()).isEqualTo(DAY);
        assertThat(assignments.get(0).assignedBy()).isEqualTo("Даша");
        assertThat(assignments.get(0).assignedAt()).isNotNull();

        assertThat(repository.unassign("AERIS", "2", DAY)).isTrue();
        assertThat(repository.unassign("AERIS", "2", DAY)).isFalse();
        assertThat(repository.assignments("AERIS", DAY)).extracting(TableWaiterAssignment::tableCode).containsExactly("1", "10");
    }
}
