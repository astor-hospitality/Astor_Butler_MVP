package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

/**
 * The same proof photo scenarios on a real PostgreSQL: the evidence migration, its foreign keys to the task
 * and to the audit line, and the tenant-locked transaction. Uses the staff fixture database of
 * PostgresStaffPortalTest and is skipped where it is not given.
 */
@EnabledIfEnvironmentVariable(named="ASTOR_STAFF_TEST_JDBC", matches=".+")
class PostgresStaffEvidenceTest extends StaffEvidenceScenarios {
    private JdbcTemplate admin;
    private String schema;

    @Override DataSource database() throws Exception {
        String url = System.getenv("ASTOR_STAFF_TEST_JDBC");
        // Scratch fixtures only: refuse an arbitrary/pre-existing production database URL.
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/staff_portal_test"))
            throw new IllegalArgumentException("Staff test requires the isolated loopback fixture database");
        schema = "staff_evidence_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "test-fixture-only"));
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "postgres", "test-fixture-only");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-05-staff-portal.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-11-staff-task-evidence.sql"));
        }
        return source;
    }

    @AfterEach void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }
}
