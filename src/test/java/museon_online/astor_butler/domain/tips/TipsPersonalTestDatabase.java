package museon_online.astor_butler.domain.tips;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * A fresh in-memory database built from the real staff-directory and tips migrations, so the tests run the SQL
 * production runs. The connection that ran the DDL stays open for the whole test: H2 evaluates the directory's CHECK
 * constraint through the session that created its table.
 */
final class TipsPersonalTestDatabase implements AutoCloseable {

    private final Connection schema;
    private final JdbcTemplate jdbc;

    private TipsPersonalTestDatabase(Connection schema, JdbcTemplate jdbc) {
        this.schema = schema;
        this.jdbc = jdbc;
    }

    static TipsPersonalTestDatabase create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tips-personal-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            Connection schema = dataSource.getConnection();
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-05-staff-portal.sql"));
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-12-tips-personal.sql"));
            return new TipsPersonalTestDatabase(schema, new JdbcTemplate(dataSource));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the tips test database", e);
        }
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = schema; Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
