package museon_online.astor_butler.domain.booking;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** A fresh in-memory database built from the real morning-check migration, so the tests run the SQL production runs. */
final class BookingMorningCheckTestDatabase {

    private BookingMorningCheckTestDatabase() {
    }

    static JdbcTemplate create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:morning-check-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-12-booking-morning-check.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the morning check test database", e);
        }
        return new JdbcTemplate(dataSource);
    }
}
