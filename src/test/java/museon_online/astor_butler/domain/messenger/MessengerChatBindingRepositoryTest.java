package museon_online.astor_butler.domain.messenger;

import museon_online.astor_butler.domain.messenger.MessengerChatBindingRepository.Profile;
import museon_online.astor_butler.service.message.MessageChannel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static museon_online.astor_butler.domain.messenger.MessengerChatBindingRepository.INTERNAL_CHAT_ID_BASE;
import static org.assertj.core.api.Assertions.assertThat;

/** The real migration on an in-memory database: MAX chats get stable internal ids outside the Telegram range. */
class MessengerChatBindingRepositoryTest {

    private final JdbcTemplate jdbc = database();
    private final MessengerChatBindingRepository repository = new MessengerChatBindingRepository(jdbc);

    @Test
    void aDialogGetsOnePositiveInternalIdThatSurvivesLaterMessages() {
        MessengerChatBinding first = repository.resolve(MessageChannel.MAX, 5_550_001L, 77L, "dialog", false,
                new Profile("Анна", null, "anna", "ru"));
        MessengerChatBinding again = repository.resolve(MessageChannel.MAX, 5_550_001L, 77L, "dialog", false,
                new Profile(null, "Петрова", null, null));

        assertThat(first.internalChatId()).isGreaterThan(INTERNAL_CHAT_ID_BASE);
        assertThat(again.internalChatId()).isEqualTo(first.internalChatId());
        assertThat(again.externalUserId()).isEqualTo(77L);
        // Blank profile fields never erase what an earlier message told us.
        assertThat(jdbc.queryForObject("SELECT first_name FROM messenger_chat_bindings WHERE external_chat_id = 5550001",
                String.class)).isEqualTo("Анна");
        assertThat(jdbc.queryForObject("SELECT last_name FROM messenger_chat_bindings WHERE external_chat_id = 5550001",
                String.class)).isEqualTo("Петрова");
    }

    @Test
    void differentChatsGetDifferentIdsAndGroupChatsAreNegative() {
        long dialogA = repository.resolve(MessageChannel.MAX, 1L, 10L, "dialog", false, Profile.empty()).internalChatId();
        long dialogB = repository.resolve(MessageChannel.MAX, 2L, 11L, "dialog", false, Profile.empty()).internalChatId();
        long group = repository.resolve(MessageChannel.MAX, -3L, null, "chat", true, Profile.empty()).internalChatId();

        assertThat(dialogA).isNotEqualTo(dialogB);
        assertThat(group).isLessThan(-INTERNAL_CHAT_ID_BASE);
    }

    @Test
    void reverseLookupAndContactPhone() {
        MessengerChatBinding binding = repository.resolve(MessageChannel.MAX, 42L, 420L, "dialog", false, Profile.empty());
        repository.saveContactPhone(MessageChannel.MAX, 42L, " +79990000000 ");

        assertThat(repository.findByInternalChatId(binding.internalChatId())).hasValueSatisfying(found -> {
            assertThat(found.externalChatId()).isEqualTo(42L);
            assertThat(found.channel()).isEqualTo(MessageChannel.MAX);
            assertThat(found.contactPhone()).isEqualTo("+79990000000");
        });
        assertThat(repository.findByInternalChatId(123L)).isEmpty();
    }

    private static JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:messenger-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-10-max-channel.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the messenger test database", e);
        }
        return new JdbcTemplate(dataSource);
    }
}
