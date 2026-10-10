package museon_online.astor_butler.domain.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MAX consent lives in user_consents without a Telegram id, keyed by the internal MAX chat id. */
class ConsentVaultServiceMaxTest {

    private static final long CHAT_ID = 8_000_000_000_011L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConsentVaultService service = new ConsentVaultService(jdbc, new ObjectMapper());

    @Test
    void firstContactInsertsAGrantedRowWithoutTelegramId() {
        when(jdbc.update(contains("UPDATE user_consents"), any(Object[].class))).thenReturn(0);

        service.grantPrivacyPolicyFromMaxContact(maxContact());

        ArgumentCaptor<Object[]> insert = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO user_consents"), insert.capture());
        assertThat(insert.getValue()).contains(CHAT_ID, ConsentVaultService.PRIVACY_POLICY,
                ConsentVaultService.CURRENT_POLICY_VERSION, ConsentVaultService.MAX_CONTACT_SOURCE);
    }

    @Test
    void repeatedContactRefreshesTheExistingRow() {
        when(jdbc.update(contains("UPDATE user_consents"), any(Object[].class))).thenReturn(1);

        service.grantPrivacyPolicyFromMaxContact(maxContact());

        verify(jdbc, never()).update(contains("INSERT INTO user_consents"), any(Object[].class));
    }

    @Test
    void telegramMessagesNeverWriteMaxConsent() {
        IncomingMessage telegram = IncomingMessage.telegram(1L, 1L, 1, 1, "", "+7999", null, null, null, null, false, "c");

        service.grantPrivacyPolicyFromMaxContact(telegram);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupIsByInternalChatIdAndMaxSource() {
        when(jdbc.query(contains("telegram_user_id IS NULL"), any(ResultSetExtractor.class), any(), any(), any(), any()))
                .thenReturn(Boolean.TRUE);

        assertThat(service.hasGrantedMaxPrivacyPolicy(CHAT_ID)).isTrue();
        assertThat(service.hasGrantedMaxPrivacyPolicy(null)).isFalse();
        verify(jdbc, times(1)).query(contains("chat_id = ?"), any(ResultSetExtractor.class), any(), any(), any(), any());
    }

    private IncomingMessage maxContact() {
        return IncomingMessage.max(CHAT_ID, 9_123_456L, "", "+79990000000", "Анна", null, null, "ru", false, "max-7",
                Map.of("maxChatId", 555L, "maxMessageId", "mid.1"));
    }
}
