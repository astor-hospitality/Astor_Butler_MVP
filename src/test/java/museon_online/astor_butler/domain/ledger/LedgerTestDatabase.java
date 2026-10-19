package museon_online.astor_butler.domain.ledger;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** A fresh in-memory database built from the real visit-turns migration. */
public final class LedgerTestDatabase implements AutoCloseable {

    private final Connection schema;
    private final JdbcTemplate jdbc;

    private LedgerTestDatabase(Connection schema, JdbcTemplate jdbc) {
        this.schema = schema;
        this.jdbc = jdbc;
    }

    public static LedgerTestDatabase create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ledger-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            Connection schema = dataSource.getConnection();
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-visit-turns.sql"));
            return new LedgerTestDatabase(schema, new JdbcTemplate(dataSource));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the ledger test database", e);
        }
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = schema; Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
