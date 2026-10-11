package museon_online.astor_butler.fsm.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * What the tip dialogue remembers between the guest's answers: the check the percents are counted from and the tip
 * waiting for confirmation, if the guest went back to change its size. Kept an hour; without it the tip question
 * falls back to a sum in rubles, as it was before percents.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TipDraftStorage {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${astor.redis.key-prefix:astor}")
    private String keyPrefix;

    @Value("${astor.tip.draft-ttl-seconds:3600}")
    private long draftTtlSeconds;

    /** Where the check came from: the venue's sum, Butler's own count by the menu, or the guest's word. */
    public enum CheckSource {
        VENUE,
        ESTIMATE,
        GUEST
    }

    /**
     * @param checkMinor the check in kopecks; null until known
     * @param billId the guest's bill the check was taken from; null when the guest named it
     * @param awaitingCheck the last question was "what is the check"
     * @param pendingPercent a percent the guest named before the check was known
     * @param tipOrderId the tip waiting for confirmation whose size the guest is changing
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Draft(
            Long checkMinor,
            CheckSource checkSource,
            Long billId,
            boolean awaitingCheck,
            Integer pendingPercent,
            Long tipOrderId
    ) {
        public static Draft empty() {
            return new Draft(null, null, null, false, null, null);
        }

        public boolean knowsCheck() {
            return checkMinor != null && checkMinor > 0;
        }

        public Draft withCheck(long minor, CheckSource source, Long fromBill) {
            return new Draft(minor, source, fromBill, false, pendingPercent, tipOrderId);
        }

        public Draft askingForCheck(Integer percent) {
            return new Draft(checkMinor, checkSource, billId, true, percent, tipOrderId);
        }

        /** The percent question is on the screen. */
        public Draft choosing() {
            return new Draft(checkMinor, checkSource, billId, false, null, tipOrderId);
        }

        public Draft withOrder(Long orderId) {
            return new Draft(checkMinor, checkSource, billId, false, null, orderId);
        }
    }

    public void save(Long chatId, Draft draft) {
        if (chatId == null || draft == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(chatId), objectMapper.writeValueAsString(draft), Duration.ofSeconds(draftTtlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Tip draft serialization failed: chatId={}, reason={}", chatId, e.getMessage());
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
            log.warn("Tip draft deserialization failed: chatId={}, reason={}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    public void clear(Long chatId) {
        if (chatId != null) {
            redisTemplate.delete(key(chatId));
        }
    }

    private String key(Long chatId) {
        return keyPrefix + ":tip:draft:" + chatId;
    }
}
