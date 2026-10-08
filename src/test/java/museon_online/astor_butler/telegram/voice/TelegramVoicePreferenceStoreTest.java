package museon_online.astor_butler.telegram.voice;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** H2 in PostgreSQL mode with the real migration on top of the profile columns the store touches. */
class TelegramVoicePreferenceStoreTest {

    private final JdbcTemplate jdbc = database();
    private final TelegramVoicePreferenceStore store = new TelegramVoicePreferenceStore(jdbc);

    @Test
    void handsFreeIsOffByDefaultAndPersistsOnTheProfile() {
        jdbc.update("INSERT INTO telegram_profiles (telegram_user_id, chat_id, source_channel) VALUES (1, 1, 'TELEGRAM')");

        assertThat(store.isHandsFree(1L)).isFalse();
        store.setHandsFree(1L, 1L, true);
        assertThat(store.isHandsFree(1L)).isTrue();
        store.setHandsFree(1L, 1L, false);
        assertThat(store.isHandsFree(1L)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telegram_profiles", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT voice_replies_updated_at FROM telegram_profiles WHERE telegram_user_id = 1", Timestamp.class)).isNotNull();
    }

    @Test
    void firstEverMessageBeingTheCommandCreatesAMinimalProfile() {
        assertThat(store.isHandsFree(2L)).isFalse();

        store.setHandsFree(2L, 2L, true);

        assertThat(store.isHandsFree(2L)).isTrue();
        assertThat(jdbc.queryForObject("SELECT source_channel FROM telegram_profiles WHERE telegram_user_id = 2", String.class)).isEqualTo("TELEGRAM");
        assertThat(store.isHandsFree(null)).isFalse();
    }

    @Test
    void fullReplyIsReadableOnlyFromItsChatAndPrunedByAge() {
        long id = store.saveFullReply(10L, "<b>Полный</b> ответ", true, Duration.ofDays(7));

        assertThat(id).isPositive();
        Optional<TelegramVoicePreferenceStore.FullReply> own = store.findFullReply(id, 10L);
        assertThat(own).hasValueSatisfying(reply -> {
            assertThat(reply.text()).isEqualTo("<b>Полный</b> ответ");
            assertThat(reply.html()).isTrue();
        });
        assertThat(store.findFullReply(id, 11L)).isEmpty();
        assertThat(store.findFullReply(id, null)).isEmpty();

        jdbc.update("UPDATE telegram_reply_details SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(8))), id);
        long next = store.saveFullReply(10L, "свежий", false, Duration.ofDays(7));
        assertThat(store.findFullReply(id, 10L)).isEmpty();
        assertThat(store.findFullReply(next, 10L)).hasValueSatisfying(reply -> assertThat(reply.html()).isFalse());
    }

    private static JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:voice-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    CREATE TABLE telegram_profiles (
                        telegram_user_id BIGINT PRIMARY KEY,
                        chat_id BIGINT,
                        source_channel VARCHAR(32) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-08-telegram-voice-replies.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot build the voice test database", e);
        }
        return new JdbcTemplate(dataSource);
    }
}
