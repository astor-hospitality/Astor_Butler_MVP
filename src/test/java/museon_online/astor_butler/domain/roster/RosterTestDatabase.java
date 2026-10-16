package museon_online.astor_butler.domain.roster;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * A fresh in-memory database built from the real staff-directory and schedule migrations, so the tests run the SQL
 * production runs. The connection that ran the DDL stays open for the whole test (H2 checks the directory's CHECK
 * constraint through it). The directory is filled with made-up people only.
 */
final class RosterTestDatabase implements AutoCloseable {

    private final Connection schema;
    private final JdbcTemplate jdbc;

    private RosterTestDatabase(Connection schema, JdbcTemplate jdbc) {
        this.schema = schema;
        this.jdbc = jdbc;
    }

    static RosterTestDatabase create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:roster-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            Connection schema = dataSource.getConnection();
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-05-staff-portal.sql"));
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-16-staff-shift-schedule.sql"));
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-12-tips-personal.sql"));
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-16-waiter-zones.sql"));
            return new RosterTestDatabase(schema, new JdbcTemplate(dataSource));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the roster test database", e);
        }
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** The made-up directory the fixture picture is matched against. Пётр Неизвестный has left (inactive). */
    void directory() {
        member("alisa", "Алиса Тестова", "HOSTESS", true);
        member("boris", "Борис Примеров", "MANAGER", true);
        member("vera", "Вера Образцова", "WAITER", true);
        member("gleb", "Глеб Макетов", "WAITER", true);
        member("dina.sh", "Дина Шаблонова", "WAITER", true);
        member("dina.pr", "Дина Пробная", "HOSTESS", true);
        member("egor", "Егор", "WAITER", true);
        member("petr", "Пётр Неизвестный", "WAITER", false);
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('OTHER', 'vera', 'Вера Другая', 'WAITER', TRUE)");
    }

    void member(String staffId, String name, String role, boolean active) {
        jdbc.update("INSERT INTO astor_staff_members(tenant, staff_id, display_name, role, active) VALUES ('AERIS', ?, ?, ?, ?)",
                staffId, name, role, active);
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = schema; Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
