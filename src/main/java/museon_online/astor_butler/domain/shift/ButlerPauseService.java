package museon_online.astor_butler.domain.shift;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The kill switch of the shift (#134), kept in Redis next to the FSM state and the hostess manual mode: no TTL, so a
 * restart does not quietly bring the automation back; the manager's {@code /start_butler} does.
 *
 * Everything is behind {@code astor.kill-switch.enabled} (default off): with the flag off the commands are unknown and
 * {@link #paused()} is always false, whatever Redis remembers from an earlier run.
 */
@Service
@Slf4j
public class ButlerPauseService {

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final String keyPrefix;
    private Clock clock = Clock.systemUTC();

    public ButlerPauseService(StringRedisTemplate redisTemplate,
                              @Value("${astor.kill-switch.enabled:false}") boolean enabled,
                              @Value("${astor.redis.key-prefix:astor}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "astor" : keyPrefix;
    }

    public boolean enabled() {
        return enabled;
    }

    /** True only while the feature is on and the manager has stopped Butler; a Redis hiccup reads as "running". */
    public boolean paused() {
        return enabled && state().paused();
    }

    public ButlerPauseState state() {
        if (!enabled) {
            return ButlerPauseState.running();
        }
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key());
            if (entries == null || entries.isEmpty()) {
                return ButlerPauseState.running();
            }
            return new ButlerPauseState(
                    Boolean.parseBoolean(text(entries.get("paused"))),
                    blankToNull(text(entries.get("switchedBy"))),
                    instant(text(entries.get("switchedAt")))
            );
        } catch (RuntimeException e) {
            log.warn("Butler pause state is not readable, treating as running: {}", e.toString());
            return ButlerPauseState.running();
        }
    }

    /** Stops Butler; {@code false} when it was already stopped (the second /stop changes nothing, not even who/when). */
    public boolean stop(String by) {
        if (state().paused()) {
            return false;
        }
        save(new ButlerPauseState(true, by, clock.instant()));
        log.info("Butler paused by {}", by);
        return true;
    }

    /** Starts Butler again; {@code false} when it was not stopped. */
    public boolean resume(String by) {
        if (!state().paused()) {
            return false;
        }
        save(new ButlerPauseState(false, by, clock.instant()));
        log.info("Butler resumed by {}", by);
        return true;
    }

    private void save(ButlerPauseState state) {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("paused", Boolean.toString(state.paused()));
        entries.put("switchedBy", state.switchedBy() == null ? "" : state.switchedBy());
        entries.put("switchedAt", state.switchedAt() == null ? "" : state.switchedAt().toString());
        redisTemplate.opsForHash().putAll(key(), entries);
    }

    String key() {
        return keyPrefix + ":butler:paused";
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
