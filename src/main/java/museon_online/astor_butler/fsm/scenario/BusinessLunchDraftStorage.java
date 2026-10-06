package museon_online.astor_butler.fsm.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessLunchDraftStorage {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${astor.redis.key-prefix:astor}")
    private String keyPrefix;

    @Value("${astor.business-lunch.draft-ttl-seconds:86400}")
    private long draftTtlSeconds;

    public void save(Long chatId, Draft draft) {
        if (chatId == null || draft == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(chatId), objectMapper.writeValueAsString(draft), Duration.ofSeconds(draftTtlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Business lunch draft serialization failed: chatId={}, reason={}", chatId, e.getMessage());
        }
    }

    public Optional<Draft> find(Long chatId) {
        if (chatId == null) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(key(chatId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, Draft.class));
        } catch (JsonProcessingException e) {
            log.warn("Business lunch draft deserialization failed: chatId={}, reason={}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    public void clear(Long chatId) {
        if (chatId != null) {
            redisTemplate.delete(key(chatId));
        }
    }

    private String key(Long chatId) {
        return keyPrefix + ":lunch:draft:telegram:" + chatId;
    }

    /**
     * What the guest has chosen so far. {@code dishCodes} has one place per slot of the chosen set, empty until picked.
     * {@code source} is CONCIERGE for a guest the Concierge sent and DIRECT for one who asked the bot.
     */
    public record Draft(
            String venueCode,
            String setCode,
            List<String> dishCodes,
            Integer partySize,
            LocalDate date,
            LocalTime time,
            String comment,
            String source,
            String conciergeRequestId
    ) {
        public Draft {
            dishCodes = dishCodes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(dishCodes));
        }

        public static Draft start(String venueCode, String source, String conciergeRequestId) {
            return new Draft(venueCode, null, List.of(), null, null, null, null, source, conciergeRequestId);
        }

        public Draft withSet(String code, List<String> dishes) {
            return new Draft(venueCode, code, dishes, partySize, date, time, comment, source, conciergeRequestId);
        }

        public Draft withDish(int slot, String dishCode) {
            List<String> dishes = new ArrayList<>(dishCodes);
            dishes.set(slot, dishCode);
            return new Draft(venueCode, setCode, dishes, partySize, date, time, comment, source, conciergeRequestId);
        }

        public Draft withPartySize(Integer guests) {
            return new Draft(venueCode, setCode, dishCodes, guests, date, time, comment, source, conciergeRequestId);
        }

        public Draft withDate(LocalDate day) {
            return new Draft(venueCode, setCode, dishCodes, partySize, day, time, comment, source, conciergeRequestId);
        }

        public Draft withTime(LocalTime at) {
            return new Draft(venueCode, setCode, dishCodes, partySize, date, at, comment, source, conciergeRequestId);
        }

        public Draft withComment(String wish) {
            return new Draft(venueCode, setCode, dishCodes, partySize, date, time, wish, source, conciergeRequestId);
        }
    }
}
