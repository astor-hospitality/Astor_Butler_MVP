package museon_online.astor_butler.domain.entry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The real migration on an in-memory database: the latest entry of a chat, and the name from the staff directory. */
class GuestEntryEventRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    private final JdbcTemplate jdbc = GuestEntryTestDatabase.create();
    private final GuestEntryEventRepository repository = new GuestEntryEventRepository(jdbc);

    @Test
    void theLatestEntryOfTheChatWithinTheWindowIsFound() {
        repository.insert("aeris", 1001L, "ilya", null, "STAFF_HANDOFF", NOW.minus(Duration.ofHours(2)));
        repository.insert("aeris", 1001L, "anna", "5", "STAFF_HANDOFF", NOW.minus(Duration.ofMinutes(10)));
        repository.insert("aeris", 1002L, "host", null, "STAFF_HANDOFF", NOW);

        assertThat(repository.latestForChat(1001L, NOW.minus(Duration.ofHours(6)))).hasValueSatisfying(event -> {
            assertThat(event.id()).isNotNull();
            assertThat(event.tenant()).isEqualTo("aeris");
            assertThat(event.chatId()).isEqualTo(1001L);
            assertThat(event.staffId()).isEqualTo("anna");
            assertThat(event.tableCode()).isEqualTo("5");
            assertThat(event.source()).isEqualTo("STAFF_HANDOFF");
            assertThat(event.createdAt()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        });
        assertThat(repository.latestForChat(1002L, NOW.minus(Duration.ofHours(6)))).hasValueSatisfying(event ->
                assertThat(event.staffId()).isEqualTo("host"));
        // Older than the window, or another chat: nothing.
        assertThat(repository.latestForChat(1001L, NOW.minus(Duration.ofMinutes(5)))).isEmpty();
        assertThat(repository.latestForChat(1003L, NOW.minus(Duration.ofHours(6)))).isEmpty();
    }

    @Test
    void aBlankTenantAndTableAreStoredAsNull() {
        repository.insert("", 1001L, "anna", " ", "STAFF_HANDOFF", NOW);

        assertThat(repository.latestForChat(1001L, NOW.minus(Duration.ofHours(1)))).hasValueSatisfying(event -> {
            assertThat(event.tenant()).isNull();
            assertThat(event.tableCode()).isNull();
        });
    }

    @Test
    void theDisplayNameComesFromTheDirectoryByTenantOrFromAnyTenant() {
        GuestEntryTestDatabase.member(jdbc, "aeris", "anna", "Анна", true);
        GuestEntryTestDatabase.member(jdbc, "c3flex", "anna", "Анна К.", true);

        assertThat(repository.staffDisplayName("aeris", "anna")).contains("Анна");
        assertThat(repository.staffDisplayName("c3flex", "anna")).contains("Анна К.");
        assertThat(repository.staffDisplayName("", "anna")).isPresent();
        assertThat(repository.staffDisplayName(null, "anna")).isPresent();
        assertThat(repository.staffDisplayName("aeris", "nobody")).isEmpty();
        assertThat(repository.staffDisplayName("aeris", "")).isEmpty();
        assertThat(repository.staffDisplayName("other", "anna")).isEmpty();
    }
}
