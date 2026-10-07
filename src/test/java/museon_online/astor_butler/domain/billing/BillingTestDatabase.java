package museon_online.astor_butler.domain.billing;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** A fresh in-memory database built from the real billing migration, so the tests run the SQL that production runs. */
public final class BillingTestDatabase {

    private BillingTestDatabase() {
    }

    public static JdbcTemplate create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:billing-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-07-guest-billing.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-08-guest-billing-payment-review.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the billing test database", e);
        }
        return new JdbcTemplate(dataSource);
    }
}
