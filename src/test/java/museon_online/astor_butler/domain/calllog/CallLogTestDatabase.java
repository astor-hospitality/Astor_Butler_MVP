package museon_online.astor_butler.domain.calllog;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** A fresh in-memory database built from the real call-log migration, so the tests run the SQL that production runs. */
public final class CallLogTestDatabase {

    private CallLogTestDatabase() {
    }

    public static JdbcTemplate create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:call-log-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-15-call-log.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the call log test database", e);
        }
        return new JdbcTemplate(dataSource);
    }
}
