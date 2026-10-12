package museon_online.astor_butler.domain.shift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The pause flag lives in Redis without a TTL, next to the FSM state; the feature flag decides whether it means anything. */
class ButlerPauseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final String KEY = "astor:butler:paused";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final ButlerPauseService service = new ButlerPauseService(redis, true, "astor");

    @BeforeEach
    void redisOperations() {
        when(redis.opsForHash()).thenReturn(hashes);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void runningWhileNothingWasEverSwitched() {
        when(hashes.entries(KEY)).thenReturn(Map.of());

        assertThat(service.paused()).isFalse();
        assertThat(service.state()).isEqualTo(ButlerPauseState.running());
    }

    @Test
    void stopIsStoredWithWhoAndWhenAndReadBack() {
        when(hashes.entries(KEY)).thenReturn(Map.of());

        assertThat(service.stop("Даша (@dasha)")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(eq(KEY), written.capture());
        assertThat(written.getValue()).containsEntry("paused", "true")
                .containsEntry("switchedBy", "Даша (@dasha)")
                .containsEntry("switchedAt", "2026-10-12T15:00:00Z");

        when(hashes.entries(KEY)).thenReturn(Map.copyOf(written.getValue()));
        assertThat(service.paused()).isTrue();
        ButlerPauseState state = service.state();
        assertThat(state.switchedBy()).isEqualTo("Даша (@dasha)");
        assertThat(state.switchedAt()).isEqualTo(NOW);
    }

    @Test
    void aSecondStopChangesNothing() {
        when(hashes.entries(KEY)).thenReturn(Map.of("paused", "true", "switchedBy", "@dasha", "switchedAt", NOW.toString()));

        assertThat(service.stop("@someone")).isFalse();

        verify(hashes, never()).putAll(anyString(), any());
    }

    @Test
    void resumeClearsThePauseAndRememberWhoAndResumeTwiceIsANoOp() {
        when(hashes.entries(KEY)).thenReturn(Map.of("paused", "true", "switchedBy", "@dasha", "switchedAt", NOW.toString()));

        assertThat(service.resume("@manager")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(eq(KEY), written.capture());
        assertThat(written.getValue()).containsEntry("paused", "false").containsEntry("switchedBy", "@manager");

        when(hashes.entries(KEY)).thenReturn(Map.copyOf(written.getValue()));
        assertThat(service.resume("@manager")).isFalse();
        verify(hashes, times(1)).putAll(anyString(), any());
    }

    @Test
    void withTheFeatureOffRedisIsNotEvenAskedAndNothingIsPaused() {
        ButlerPauseService off = new ButlerPauseService(redis, false, "astor");
        when(hashes.entries(KEY)).thenReturn(Map.of("paused", "true"));

        assertThat(off.enabled()).isFalse();
        assertThat(off.paused()).isFalse();
        verify(hashes, never()).entries(anyString());
    }

    @Test
    void aRedisFailureReadsAsRunning() {
        when(hashes.entries(KEY)).thenThrow(new IllegalStateException("redis down"));

        assertThat(service.paused()).isFalse();
    }
}
