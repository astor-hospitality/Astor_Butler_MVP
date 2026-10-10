package museon_online.astor_butler.domain.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebChatRateLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final WebChatRateLimiter limiter = new WebChatRateLimiter(redisTemplate, new SimpleMeterRegistry());

    @Test
    void deniesWhenBurstWindowIsExceededForSession() {
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "maxBurst", 1L);
        ReflectionTestUtils.setField(limiter, "maxPerMinute", 10L);
        ReflectionTestUtils.setField(limiter, "burstWindowSeconds", 10L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(startsWith("web-chat:rate:burst:session:session-1"))).thenReturn(2L);

        WebChatRateLimiter.Decision decision = limiter.check(
                "203.0.113.10",
                "web:anon:session-1",
                null,
                Map.of("sessionId", "session-1")
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("burst");
        assertThat(decision.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void deniesWhenTheClientIpExceedsItsMinuteWindowEvenWithFreshSessions() {
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "maxBurst", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinute", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinutePerIp", 60L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(startsWith("web-chat:rate:burst:"))).thenReturn(1L);
        when(valueOperations.increment(startsWith("web-chat:rate:minute:"))).thenReturn(1L);
        when(valueOperations.increment(startsWith("web-chat:rate:ip:203.0.113.10"))).thenReturn(61L);

        WebChatRateLimiter.Decision decision = limiter.check("203.0.113.10", null, null, Map.of("sessionId", "fresh-session-61"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("ip");
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void ipWindowIsSkippedWhenDisabledOrWhenTheIpIsAlreadyTheKey() {
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "maxBurst", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinute", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinutePerIp", 0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(1L);

        assertThat(limiter.check("203.0.113.10", null, null, Map.of("sessionId", "s")).allowed()).isTrue();
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never()).increment(startsWith("web-chat:rate:ip:"));

        ReflectionTestUtils.setField(limiter, "maxPerMinutePerIp", 60L);
        assertThat(limiter.check("203.0.113.10", null, null, Map.of()).allowed()).isTrue();
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never()).increment(startsWith("web-chat:rate:ip:"));
    }

    @Test
    void capsNeverSeenSessionsPerAddressAndForgetsTheRejectedOne() {
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "maxBurst", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinute", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinutePerIp", 0L);
        ReflectionTestUtils.setField(limiter, "maxNewSessionsPerMinutePerIp", 5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("web-chat:session-seen:"), any(), any(java.time.Duration.class))).thenReturn(true);
        when(valueOperations.increment(startsWith("web-chat:rate:newsessions:203.0.113.10"))).thenReturn(6L);

        WebChatRateLimiter.Decision decision = limiter.check("203.0.113.10", null, null, Map.of("sessionId", "web-rotated-6"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("new_sessions");
        org.mockito.Mockito.verify(redisTemplate).delete("web-chat:session-seen:web-rotated-6");
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never()).increment(startsWith("web-chat:rate:burst:"));
    }

    @Test
    void knownSessionsDoNotCountAgainstTheNewSessionWindow() {
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "maxBurst", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinute", 10L);
        ReflectionTestUtils.setField(limiter, "maxPerMinutePerIp", 0L);
        ReflectionTestUtils.setField(limiter, "maxNewSessionsPerMinutePerIp", 5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("web-chat:session-seen:"), any(), any(java.time.Duration.class))).thenReturn(false);
        when(valueOperations.increment(any())).thenReturn(1L);

        assertThat(limiter.check("203.0.113.10", null, null, Map.of("sessionId", "web-known")).allowed()).isTrue();
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never()).increment(startsWith("web-chat:rate:newsessions:"));
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        ReflectionTestUtils.setField(limiter, "enabled", true);
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        WebChatRateLimiter.Decision decision = limiter.check("203.0.113.10", null, null, Map.of());

        assertThat(decision.allowed()).isTrue();
    }
}
