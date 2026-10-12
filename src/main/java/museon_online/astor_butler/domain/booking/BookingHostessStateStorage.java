package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the confirmation timer has to remember between runs, kept in Redis next to the FSM state: the manual mode of
 * the venue, which Telegram message is the hostess card of an order (to annotate it later) and which orders are already
 * with the manager (so a card is forwarded once and is not confirmed behind the manager's back).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingHostessStateStorage {

    /** A card older than this is not annotated any more; Telegram does not let a bot edit old messages anyway. */
    static final Duration CARD_TTL = Duration.ofDays(14);

    private final StringRedisTemplate redisTemplate;

    @Value("${astor.redis.key-prefix:astor}")
    private String keyPrefix;

    /** The hostess card of an order: the chat it went to and the message id there. */
    public record CardRef(String chatId, Integer messageId) {
    }

    public HostessManualMode manualMode() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(manualModeKey());
        if (entries == null || entries.isEmpty()) {
            return HostessManualMode.auto();
        }
        return new HostessManualMode(
                Boolean.parseBoolean(text(entries.get("manual"))),
                blankToNull(text(entries.get("reason"))),
                blankToNull(text(entries.get("switchedBy"))),
                instant(text(entries.get("switchedAt")))
        );
    }

    public void saveManualMode(HostessManualMode mode) {
        if (mode == null) {
            return;
        }
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("manual", Boolean.toString(mode.manual()));
        entries.put("reason", nullToBlank(mode.reason()));
        entries.put("switchedBy", nullToBlank(mode.switchedBy()));
        entries.put("switchedAt", mode.switchedAt() == null ? "" : mode.switchedAt().toString());
        redisTemplate.opsForHash().putAll(manualModeKey(), entries);
    }

    public void rememberHostessCard(Long orderId, String chatId, Integer messageId) {
        if (orderId == null || chatId == null || chatId.isBlank() || messageId == null) {
            return;
        }
        redisTemplate.opsForValue().set(cardKey(orderId), chatId + ":" + messageId, CARD_TTL);
    }

    public Optional<CardRef> hostessCard(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(cardKey(orderId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CardRef(value.substring(0, split), Integer.parseInt(value.substring(split + 1))));
        } catch (NumberFormatException e) {
            log.debug("Hostess card reference is not readable: orderId={}, value={}", orderId, value);
            return Optional.empty();
        }
    }

    /** True the first time only: the caller that gets {@code true} is the one to forward the card. */
    public boolean markEscalated(Long orderId) {
        if (orderId == null) {
            return false;
        }
        Boolean set = redisTemplate.opsForValue().setIfAbsent(escalatedKey(orderId), Instant.now().toString(), CARD_TTL);
        return Boolean.TRUE.equals(set);
    }

    public boolean escalated(Long orderId) {
        return orderId != null && Boolean.TRUE.equals(redisTemplate.hasKey(escalatedKey(orderId)));
    }

    private String manualModeKey() {
        return keyPrefix + ":booking:hostess:manual-mode";
    }

    private String cardKey(Long orderId) {
        return keyPrefix + ":booking:hostess:card:" + orderId;
    }

    private String escalatedKey(Long orderId) {
        return keyPrefix + ":booking:hostess:escalated:" + orderId;
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

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
