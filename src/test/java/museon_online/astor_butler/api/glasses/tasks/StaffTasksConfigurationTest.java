package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Task persistence is its own switch: on for the glasses while the JWT portal stays off. */
class StaffTasksConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(StaffTasksConfiguration.class, StaffPortalController.class);

    @Test void offByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(StaffPortalService.class);
            assertThat(context).doesNotHaveBean(StaffPortalController.class);
        });
    }

    @Test void tasksAloneGiveTheServiceButNoPortalRoutes() {
        runner.withPropertyValues("astor.staff.tasks-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(StaffPortalService.class);
            assertThat(context).doesNotHaveBean(StaffPortalController.class);
        });
    }

    @Test void thePortalSwitchStillBringsBoth() {
        runner.withPropertyValues("astor.staff.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(StaffPortalService.class);
            assertThat(context).hasSingleBean(StaffPortalController.class);
        });
        runner.withPropertyValues("astor.staff.enabled=true", "astor.staff.tasks-enabled=true").run(context ->
                assertThat(context).hasSingleBean(StaffPortalService.class));
    }
}
