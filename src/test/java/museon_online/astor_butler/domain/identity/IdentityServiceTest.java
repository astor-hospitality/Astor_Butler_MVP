package museon_online.astor_butler.domain.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The read side of the guest's contacts, against the tables {@code 2026-06-04-normalize-identity.sql} describes. */
class IdentityServiceTest {

    private static final long GUEST = 1773317437L;

    private JdbcTemplate jdbcTemplate;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:identity-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    telegram_id BIGINT UNIQUE,
                    phone VARCHAR(64)
                )
                """);
        // The same columns and constraint as the migration, so the query runs against what production has.
        jdbcTemplate.execute("""
                CREATE TABLE user_contacts (
                    id UUID PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    contact_type VARCHAR(32) NOT NULL,
                    contact_value TEXT NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    is_primary BOOLEAN NOT NULL DEFAULT false,
                    verified_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_user_contacts_user_type_value UNIQUE (user_id, contact_type, contact_value)
                )
                """);
        jdbcTemplate.update("INSERT INTO users (id, telegram_id) VALUES (1, ?)", GUEST);
        jdbcTemplate.update("INSERT INTO users (id, telegram_id) VALUES (2, ?)", GUEST + 1);
        service = new IdentityService(jdbcTemplate);
    }

    @Test
    void thePrimaryPhoneContactIsTheGuestsPhone() {
        contact(1, "PHONE", "+79990000001", false, OffsetDateTime.parse("2026-06-01T10:00:00Z"));
        contact(1, "PHONE", "+79990000000", true, OffsetDateTime.parse("2026-05-01T10:00:00Z"));
        contact(1, "EMAIL", "guest@example.com", true, OffsetDateTime.parse("2026-09-01T10:00:00Z"));
        contact(2, "PHONE", "+79990000002", true, OffsetDateTime.parse("2026-09-01T10:00:00Z"));

        assertThat(service.primaryPhone(GUEST)).contains("+79990000000");
    }

    @Test
    void withoutAPrimaryOneTheLatestVerifiedPhoneIsTaken() {
        contact(1, "PHONE", "+79990000001", false, OffsetDateTime.parse("2026-06-01T10:00:00Z"));
        contact(1, "PHONE", "+79990000003", false, OffsetDateTime.parse("2026-08-01T10:00:00Z"));
        contact(1, "PHONE", "+79990000004", false, null);

        assertThat(service.primaryPhone(GUEST)).contains("+79990000003");
    }

    @Test
    void aGuestWhoNeverSharedAPhoneHasNone() {
        contact(1, "PHONE", "   ", true, OffsetDateTime.parse("2026-06-01T10:00:00Z"));

        assertThat(service.primaryPhone(GUEST)).isEmpty();
        assertThat(service.primaryPhone(GUEST + 2)).isEmpty();
        assertThat(service.primaryPhone(null)).isEmpty();
    }

    private void contact(long userId, String type, String value, boolean primary, OffsetDateTime verifiedAt) {
        jdbcTemplate.update("""
                INSERT INTO user_contacts (id, user_id, contact_type, contact_value, source, is_primary, verified_at, updated_at)
                VALUES (?, ?, ?, ?, 'TELEGRAM_CONTACT', ?, ?, ?)
                """,
                UUID.randomUUID(), userId, type, value, primary, verifiedAt, verifiedAt == null ? OffsetDateTime.parse("2026-01-01T00:00:00Z") : verifiedAt);
    }
}
