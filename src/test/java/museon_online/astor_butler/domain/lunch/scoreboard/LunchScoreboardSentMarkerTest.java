package museon_online.astor_butler.domain.lunch.scoreboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One key per day, set once; a Redis that cannot answer counts as «already sent». */
class LunchScoreboardSentMarkerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final LunchScoreboardSentMarker marker = new LunchScoreboardSentMarker(redis, "astor");

    @BeforeEach
    void redisOperations() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void theFirstClaimOfADayWinsAndTheSecondDoesNot() {
        when(values.setIfAbsent(eq("astor:lunch:scoreboard:sent:2026-10-12"), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        assertThat(marker.claim(DAY)).isTrue();
        assertThat(marker.claim(DAY)).isFalse();
        verify(values, org.mockito.Mockito.times(2)).setIfAbsent(anyString(), eq("1"), eq(LunchScoreboardSentMarker.TTL));
    }

    @Test
    void aRedisFailureReadsAsAlreadySent() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("redis is away"));

        assertThat(marker.claim(DAY)).isFalse();
    }

    @Test
    void aBlankPrefixFallsBackToAstor() {
        assertThat(new LunchScoreboardSentMarker(redis, " ").key(DAY)).isEqualTo("astor:lunch:scoreboard:sent:2026-10-12");
        assertThat(new LunchScoreboardSentMarker(redis, "aeris").key(DAY)).isEqualTo("aeris:lunch:scoreboard:sent:2026-10-12");
    }
}
