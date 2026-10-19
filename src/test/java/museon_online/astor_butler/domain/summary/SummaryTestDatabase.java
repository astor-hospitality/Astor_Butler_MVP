package museon_online.astor_butler.domain.summary;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** A fresh in-memory database built from the real journey, ledger and summary migrations. */
final class SummaryTestDatabase implements AutoCloseable {

    private final Connection schema;
    private final JdbcTemplate jdbc;
    private long reservations = 1000;

    private SummaryTestDatabase(Connection schema, JdbcTemplate jdbc) {
        this.schema = schema;
        this.jdbc = jdbc;
    }

    static SummaryTestDatabase create() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:summary-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            Connection schema = dataSource.getConnection();
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-14-guest-journey.sql"));
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-visit-turns.sql"));
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-visit-summaries.sql"));
            return new SummaryTestDatabase(schema, new JdbcTemplate(dataSource));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the summary test database", e);
        }
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    long visit(String phone, Long chatId, String stage, Instant visitDay) {
        long reservation = ++reservations;
        jdbc.update("INSERT INTO guest_visits(reservation_id, chat_id, guest_phone, venue_code, stage, visit_day) "
                + "VALUES (?, ?, ?, 'AERIS', ?, ?)", reservation, chatId, phone, stage, Timestamp.from(visitDay));
        return jdbc.queryForObject("SELECT id FROM guest_visits WHERE reservation_id = ?", Long.class, reservation);
    }

    void turn(long visitId, String side, String channel, String kind, String text, String payload, Instant at) {
        jdbc.update("INSERT INTO visit_turns(visit_id, at, side, channel, kind, text, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
                visitId, Timestamp.from(at), side, channel, kind, text, payload);
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = schema; Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
