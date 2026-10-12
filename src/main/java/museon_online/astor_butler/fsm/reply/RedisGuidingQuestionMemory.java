package museon_online.astor_butler.fsm.reply;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Память о последнем направляющем вопросе в Redis рядом с FSM-состоянием чата, с коротким TTL
 * ({@code astor.dialogue.guiding-question.memory-ttl-seconds}, по умолчанию 30 минут).
 */
@Component
public class RedisGuidingQuestionMemory implements GuidingQuestionMemory {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisGuidingQuestionMemory(
            StringRedisTemplate redisTemplate,
            @Value("${astor.redis.key-prefix:astor}") String keyPrefix,
            @Value("${astor.dialogue.guiding-question.memory-ttl-seconds:1800}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    @Override
    public Optional<String> last(Long chatId) {
        String value = redisTemplate.opsForValue().get(key(chatId));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public void remember(Long chatId, String question) {
        if (question == null || question.isBlank()) {
            forget(chatId);
            return;
        }
        redisTemplate.opsForValue().set(key(chatId), question, ttl);
    }

    @Override
    public void forget(Long chatId) {
        redisTemplate.delete(key(chatId));
    }

    private String key(Long chatId) {
        return keyPrefix + ":fsm:telegram:" + chatId + ":guiding-question";
    }
}
