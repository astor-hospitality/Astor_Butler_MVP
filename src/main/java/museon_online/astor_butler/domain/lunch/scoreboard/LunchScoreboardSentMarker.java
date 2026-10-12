package museon_online.astor_butler.domain.lunch.scoreboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * One scoreboard per day: a {@code SETNX} key in Redis, the same cheap guard the Telegram idempotency uses, so a
 * restart, a second instance or a slow send never post the table twice. No table, no migration. A Redis hiccup
 * reads as «already sent»: a missing table is cheaper than a repeated one, and {@code /табло} is always there.
 */
@Component
@Slf4j
public class LunchScoreboardSentMarker {

    static final Duration TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public LunchScoreboardSentMarker(StringRedisTemplate redis,
                                     @Value("${astor.redis.key-prefix:astor}") String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "astor" : keyPrefix;
    }

    /** Takes the send of this day for the caller; false when it was already taken or Redis could not say. */
    public boolean claim(LocalDate date) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(date), "1", TTL));
        } catch (RuntimeException e) {
            log.warn("Lunch scoreboard marker for {} is not readable, treating as already sent: {}", date, e.toString());
            return false;
        }
    }

    String key(LocalDate date) {
        return keyPrefix + ":lunch:scoreboard:sent:" + date;
    }
}
