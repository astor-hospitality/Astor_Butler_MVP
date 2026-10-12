package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.AfterEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * The proof photo scenarios on a fresh in-memory database built from the two real migrations, so they run
 * on any machine without PostgreSQL and without a bucket. PostgresStaffEvidenceTest repeats them on a real
 * PostgreSQL in CI.
 */
class StaffEvidenceTest extends StaffEvidenceScenarios {
    /** Stays open for the whole test: H2 evaluates a CHECK through the session that created its table. */
    private Connection schema;

    @Override DataSource database() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:staff-evidence-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = source.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-05-staff-portal.sql"));
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-11-staff-task-evidence.sql"));
        return source;
    }

    @AfterEach void drop() throws Exception {
        if (schema == null) return;
        try (Connection connection = schema; Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
