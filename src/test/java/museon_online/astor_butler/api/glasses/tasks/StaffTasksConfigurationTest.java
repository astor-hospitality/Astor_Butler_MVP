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

    @Test void proofPhotosAreOffUntilTheirStorageIsSwitchedOnAndComplete() {
        runner.withPropertyValues("astor.staff.enabled=true").run(context ->
                assertThat(context.getBean(StaffPortalService.class).evidenceLimits()).isEmpty());
        // Every storage setting is there but the switch: still off.
        runner.withPropertyValues("astor.staff.enabled=true", "astor.staff.evidence.s3-endpoint=https://storage.example.test",
                "astor.staff.evidence.s3-bucket=unit-evidence", "astor.staff.evidence.s3-access-key=unit-key-id",
                "astor.staff.evidence.s3-secret-key=unit-key-value").run(context ->
                assertThat(context.getBean(StaffPortalService.class).evidenceLimits()).isEmpty());
        // Switched on without a bucket, or with a limit that is not a number: the application starts, photos stay off.
        runner.withPropertyValues("astor.staff.enabled=true", "astor.staff.evidence.enabled=true",
                "astor.staff.evidence.s3-endpoint=https://storage.example.test", "astor.staff.evidence.s3-access-key=unit-key-id",
                "astor.staff.evidence.s3-secret-key=unit-key-value").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StaffPortalService.class).evidenceLimits()).isEmpty();
        });
        runner.withPropertyValues("astor.staff.enabled=true", "astor.staff.evidence.enabled=true",
                "astor.staff.evidence.s3-endpoint=https://storage.example.test", "astor.staff.evidence.s3-bucket=unit-evidence",
                "astor.staff.evidence.s3-access-key=unit-key-id", "astor.staff.evidence.s3-secret-key=unit-key-value",
                "astor.staff.evidence.max-bytes=ten").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StaffPortalService.class).evidenceLimits()).isEmpty();
        });
    }

    @Test void aCompleteStorageConfigurationTurnsProofPhotosOnWithTheDefaultLimits() {
        runner.withPropertyValues("astor.staff.enabled=true", "astor.staff.evidence.enabled=true",
                "astor.staff.evidence.s3-endpoint=https://storage.example.test", "astor.staff.evidence.s3-region=ru-central1",
                "astor.staff.evidence.s3-bucket=unit-evidence", "astor.staff.evidence.s3-access-key=unit-key-id",
                "astor.staff.evidence.s3-secret-key=unit-key-value").run(context ->
                assertThat(context.getBean(StaffPortalService.class).evidenceLimits()).contains(new StaffEvidence.Limits(
                        10 * 1024 * 1024, java.util.Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"))));
        runner.withPropertyValues("astor.staff.tasks-enabled=true", "astor.staff.evidence.enabled=true",
                "astor.staff.evidence.s3-endpoint=http://minio:9000", "astor.staff.evidence.s3-bucket=unit-evidence",
                "astor.staff.evidence.s3-access-key=unit-key-id", "astor.staff.evidence.s3-secret-key=unit-key-value",
                "astor.staff.evidence.max-bytes=2097152", "astor.staff.evidence.content-types=image/jpeg").run(context ->
                assertThat(context.getBean(StaffPortalService.class).evidenceLimits())
                        .contains(new StaffEvidence.Limits(2097152, java.util.Set.of("image/jpeg"))));
    }
}
