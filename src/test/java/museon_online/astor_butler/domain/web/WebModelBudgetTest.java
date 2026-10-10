package museon_online.astor_butler.domain.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebModelBudgetTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final WebModelBudget budget = new WebModelBudget(redisTemplate, new SimpleMeterRegistry());

    private IncomingMessage incoming(MessageChannel channel, Map<String, Object> payload) {
        return new IncomingMessage(channel, "web:anon:s", 9000000000001L, 9000000000001L, null, null, "x",
                null, null, null, null, null, false, "c", Instant.now(), payload);
    }

    @Test
    void rejectsLongFreeTextBeforeTouchingRedis() {
        ReflectionTestUtils.setField(budget, "textMaxChars", 600);
        ReflectionTestUtils.setField(budget, "callsPerDayPerIp", 200L);

        WebModelBudget.Decision decision = budget.check(incoming(MessageChannel.WEB, Map.of()), "я".repeat(601));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("text_too_long");
        assertThat(decision.replyText()).contains("до 600 символов");
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void countsModelCallsPerClientKeyPerDay() {
        ReflectionTestUtils.setField(budget, "textMaxChars", 600);
        ReflectionTestUtils.setField(budget, "callsPerDayPerIp", 200L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("web-chat:model:day:ip:abc")).thenReturn(1L, 201L);
        IncomingMessage incoming = incoming(MessageChannel.WEB, Map.of(WebModelBudget.CLIENT_KEY, "ip:abc"));

        assertThat(budget.check(incoming, "коротко").allowed()).isTrue();
        verify(redisTemplate).expire(any(String.class), any(java.time.Duration.class));
        WebModelBudget.Decision exceeded = budget.check(incoming, "коротко");
        assertThat(exceeded.allowed()).isFalse();
        assertThat(exceeded.reason()).isEqualTo("daily_budget");
        assertThat(exceeded.replyText()).contains("лимит");
    }

    @Test
    void ignoresTelegramAndFailsOpenWithoutRedis() {
        ReflectionTestUtils.setField(budget, "textMaxChars", 10);
        ReflectionTestUtils.setField(budget, "callsPerDayPerIp", 1L);
        assertThat(budget.check(incoming(MessageChannel.TELEGRAM, Map.of()), "ж".repeat(5000)).allowed()).isTrue();

        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        assertThat(budget.check(incoming(MessageChannel.WEB, Map.of()), "короткий").allowed()).isTrue();
    }
}
