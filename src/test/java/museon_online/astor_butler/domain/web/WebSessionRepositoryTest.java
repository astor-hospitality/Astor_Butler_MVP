package museon_online.astor_butler.domain.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSessionRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WebSessionRepository repository = new WebSessionRepository(jdbcTemplate, new ObjectMapper());

    @SuppressWarnings("unchecked")
    private void answerUpsert(java.util.function.Function<Object[], WebSessionResolution> answer) {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    Object[] params = java.util.Arrays.copyOfRange(args, 2, args.length);
                    return answer.apply(params);
                });
    }

    @Test
    void sameSessionAlwaysDerivesTheSameChatIdInsideTheWebRange() {
        long first = WebSessionRepository.stableChatId("web-abc", 0);
        assertThat(first).isEqualTo(WebSessionRepository.stableChatId("web-abc", 0));
        assertThat(first).isBetween(9_000_000_000_000L, 9_900_000_000_000L);
        assertThat(WebSessionRepository.stableChatId("web-abc", 1)).isNotEqualTo(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rehashesWithASaltWhenAnotherSessionOwnsTheChatId() {
        AtomicInteger calls = new AtomicInteger();
        long expected = WebSessionRepository.stableChatId("web-abc", 1);
        answerUpsert(params -> {
            if (calls.incrementAndGet() == 1) {
                throw new DuplicateKeyException("duplicate key value violates unique constraint \"web_sessions_chat_id_key\"");
            }
            return new WebSessionResolution(UUID.randomUUID(), "web-abc", "web:anon:web-abc", (Long) params[4], true);
        });

        WebSessionResolution resolution = repository.upsert("astor", "web-abc", null, null, null, null, null, Map.of());

        assertThat(resolution.chatId()).isEqualTo(expected);
        assertThat(resolution.created()).isTrue();
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).queryForObject(anyString(), any(RowMapper.class), captor.capture());
        List<Object[]> attempts = captor.getAllValues();
        assertThat(attempts.get(0)[4]).isEqualTo(WebSessionRepository.stableChatId("web-abc", 0));
        assertThat(attempts.get(1)[4]).isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void givesUpAfterTheConfiguredNumberOfAttempts() {
        answerUpsert(params -> {
            throw new DuplicateKeyException("chat_id");
        });

        assertThatThrownBy(() -> repository.upsert("astor", "web-abc", null, null, null, null, null, Map.of()))
                .isInstanceOf(DuplicateKeyException.class);
        verify(jdbcTemplate, times(WebSessionRepository.MAX_CHAT_ID_ATTEMPTS)).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotRetryAnExplicitlyRequestedChatId() {
        answerUpsert(params -> {
            throw new DuplicateKeyException("chat_id");
        });

        assertThatThrownBy(() -> repository.upsert("c3ag", "web-abc", null, 123L, null, null, null, Map.of()))
                .isInstanceOf(DuplicateKeyException.class);
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
