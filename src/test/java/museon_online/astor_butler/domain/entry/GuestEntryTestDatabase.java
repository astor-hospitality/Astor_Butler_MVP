package museon_online.astor_butler.domain.entry;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A fresh in-memory database built from the real guest-entry migration, plus the staff directory as
 * 2026-10-05-staff-portal.sql defines it (the columns the lookup reads), so the tests run the SQL production runs.
 */
final class GuestEntryTestDatabase {

    private GuestEntryTestDatabase() {
    }

    static JdbcTemplate create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:guest-entry-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-12-guest-entry-events.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the guest entry test database", e);
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE astor_staff_members (
                    tenant VARCHAR(80) NOT NULL,
                    staff_id VARCHAR(128) NOT NULL,
                    display_name VARCHAR(120) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    shift_open BOOLEAN NOT NULL DEFAULT FALSE,
                    device_id VARCHAR(128),
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant, staff_id)
                )
                """);
        return jdbc;
    }

    static void member(JdbcTemplate jdbc, String tenant, String staffId, String displayName, boolean active) {
        jdbc.update("INSERT INTO astor_staff_members (tenant, staff_id, display_name, role, active) VALUES (?, ?, ?, 'WAITER', ?)",
                tenant, staffId, displayName, active);
    }
}
