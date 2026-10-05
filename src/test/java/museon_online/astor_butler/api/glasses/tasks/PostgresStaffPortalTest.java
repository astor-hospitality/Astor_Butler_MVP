package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ASTOR_STAFF_TEST_JDBC", matches=".+")
class PostgresStaffPortalTest {
    private static final StaffScope MANAGER = new StaffScope("AERIS", "manager", StaffScope.Role.MANAGER);
    private static final StaffScope ANNA = new StaffScope("AERIS", "anna", StaffScope.Role.WAITER);
    private JdbcTemplate admin, jdbc;
    private StaffPortalService portal;
    private String schema;
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("ASTOR_STAFF_TEST_JDBC");
        // Scratch fixtures only: refuse an arbitrary/pre-existing production database URL.
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/staff_portal_test"))
            throw new IllegalArgumentException("Staff test requires the isolated loopback fixture database");
        schema = "staff_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "test-fixture-only"));
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "postgres", "test-fixture-only");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-05-staff-portal.sql"));
        }
        jdbc = new JdbcTemplate(source);
        portal = new StaffPortalService(jdbc, new DataSourceTransactionManager(source));
        jdbc.update("INSERT INTO astor_staff_members(tenant,staff_id,display_name,role) VALUES ('AERIS','manager','Manager','MANAGER')");
        portal.member(MANAGER, "anna", "Anna", "WAITER", true);
        portal.member(MANAGER, "ilya", "Ilya", "WAITER", true);
        portal.shift(MANAGER, "anna", true, "glasses-1");
        portal.shift(MANAGER, "ilya", true, "glasses-2");
    }
    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    private StaffTaskService.Draft draft(String table) {
        return new StaffTaskService.Draft("Manager", table, "Prepare table", "Check setting",
                List.of(new StaffTaskService.StageDraft("setting", "Setting", false)), StaffTask.Priority.NORMAL, null, "anna");
    }
    @Test void persistedAcrossInstancesAndCompletedOnlyByItsStaff() {
        StaffTask task = portal.create(MANAGER, "create-1", draft("13"));
        var second = new StaffPortalService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThat(second.dashboard(MANAGER).tasks()).containsExactly(task);
        var accepted = second.command(ANNA, task.taskId(), "accept-1", StaffTaskService.Command.ACCEPT, 1, null);
        assertThat(accepted.version()).isEqualTo(2);
        portal.command(ANNA, task.taskId(), "stage-1", StaffTaskService.Command.STAGE_DONE, 2, "setting");
        var done = portal.command(ANNA, task.taskId(), "done-1", StaffTaskService.Command.COMPLETE, 3, null);
        assertThat(done.status()).isEqualTo(StaffTask.Status.DONE);
        assertThat(portal.history(MANAGER, task.taskId())).hasSize(4);
    }
    @Test void racingInstancesPersistOneAssignmentAndOneAuditForTheSameEvent() throws Exception {
        var second = new StaffPortalService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<StaffTask> first = executor.submit(() -> { start.await(); return portal.create(MANAGER, "same", draft("13")); });
            Future<StaffTask> other = executor.submit(() -> { start.await(); return second.create(MANAGER, "same", draft("13")); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(other.get(10, TimeUnit.SECONDS));
        }
        assertThat(portal.dashboard(MANAGER).tasks()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM astor_staff_task_audit", Integer.class)).isEqualTo(1);
    }
    @Test void eventFailureRollsBackTheTaskAndVersionTogether() {
        jdbc.execute("ALTER TABLE astor_staff_task_events ADD CONSTRAINT rejected_event CHECK (event_id <> 'reject')");
        assertThatThrownBy(() -> portal.create(MANAGER, "reject", draft("13"))).isInstanceOf(RuntimeException.class);
        assertThat(portal.dashboard(MANAGER).tasks()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM astor_staff_task_audit", Integer.class)).isZero();
    }
    @Test void shiftRevocationReassignmentAndTenantIsolationAreImmediate() {
        StaffTask task = portal.create(MANAGER, "create", draft("13"));
        portal.command(ANNA, task.taskId(), "accept", StaffTaskService.Command.ACCEPT, 1, null);
        portal.shift(MANAGER, "anna", false, null);
        assertThat(portal.snapshot(ANNA)).isEmpty();
        assertThatThrownBy(() -> portal.command(ANNA, task.taskId(), "accept", StaffTaskService.Command.ACCEPT, 1, null))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("SHIFT_CLOSED"));
        portal.shift(MANAGER, "anna", true, null);
        portal.reassign(MANAGER, task.taskId(), "reassign", 2, "ilya");
        assertThat(portal.snapshot(ANNA)).isEmpty();
        assertThatThrownBy(() -> portal.command(ANNA, task.taskId(), "accept", StaffTaskService.Command.ACCEPT, 1, null))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> portal.history(new StaffScope("OTHER", "manager", StaffScope.Role.MANAGER), task.taskId()))
                .isInstanceOf(StaffTaskFailure.class);
        assertThatThrownBy(() -> portal.cancel(ANNA, task.taskId(), "cancel", 3))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("FORBIDDEN"));
    }
    @Test void staleWritesConflictAndDisabledEmployeesCannotBeAssigned() {
        StaffTask task = portal.create(MANAGER, "create", draft("13"));
        portal.command(ANNA, task.taskId(), "accept", StaffTaskService.Command.ACCEPT, 1, null);
        assertThatThrownBy(() -> portal.cancel(MANAGER, task.taskId(), "cancel", 1))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("VERSION_CONFLICT"));
        portal.member(MANAGER, "anna", "Anna", "WAITER", false);
        assertThatThrownBy(() -> portal.create(MANAGER, "new", draft("7")))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("ASSIGNEE_OFF_SHIFT"));
    }
    @Test void directoryRoleChangeRevokesCachedManagerPrivilegesAndOnlyManagerControlsShifts() {
        portal.member(MANAGER, "hostess", "Hostess", "HOSTESS", true);
        var hostess = new StaffScope("AERIS", "hostess", StaffScope.Role.HOSTESS);
        assertThat(portal.dashboard(hostess).manageStaff()).isFalse();
        assertThatThrownBy(() -> portal.shift(hostess, "anna", false, null))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> portal.member(hostess, "other", "Other", "WAITER", true))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("FORBIDDEN"));
        portal.member(MANAGER, "manager", "Manager", "WAITER", true);
        assertThatThrownBy(() -> portal.dashboard(MANAGER))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("STAFF_INACTIVE"));
    }
    @Test void snapshotFiltersBeforeLimitAndRefusesToAdvertiseATruncatedFullSnapshot() {
        StaffTask original = portal.create(MANAGER, "original", draft("13"));
        var store = new JdbcStaffTaskStore(jdbc);
        for (int i = 0; i < 500; i++) store.insert(new StaffTask("extra-" + i, "AERIS", "Manager", "7", "Other", "",
                original.stages(), StaffTask.Priority.NORMAL, null, "ilya", StaffTask.Status.ASSIGNED, 1, null, null));
        assertThat(portal.snapshot(ANNA)).containsExactly(original);
        assertThatThrownBy(() -> portal.dashboard(MANAGER))
                .isInstanceOfSatisfying(StaffTaskFailure.class, e -> assertThat(e.code).isEqualTo("SNAPSHOT_LIMIT"));
    }
}
