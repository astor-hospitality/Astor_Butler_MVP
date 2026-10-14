package museon_online.astor_butler.domain.calllog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * The single entry point of the unified call log. Silent while {@code astor.call-log.enabled} is off; never throws,
 * so a hook can never change what the hooked code does. User ids are pseudonymized here
 * (first 24 hex of sha256(salt + ":" + rawId)); with a salt shorter than {@value #MIN_SALT_LENGTH} characters
 * no id is written at all.
 */
@Component
@Slf4j
public class CallLogWriter {

    static final int MIN_SALT_LENGTH = 16;
    private static final int PSEUDONYM_HEX = 24;

    private final CallLogProperties properties;
    private final CallLogRepository repository;
    private final ModelTariff tariff;
    private final byte[] salt;

    public CallLogWriter(CallLogProperties properties, CallLogRepository repository, ModelTariff tariff) {
        this.properties = properties;
        this.repository = repository;
        this.tariff = tariff;
        String value = properties.getSalt() == null ? "" : properties.getSalt().trim();
        if (value.length() < MIN_SALT_LENGTH) {
            if (properties.isEnabled()) {
                log.warn("ASTOR_CALL_LOG_SALT (astor.call-log.salt) is shorter than {} characters: the call log is written without user ids",
                        MIN_SALT_LENGTH);
            }
            this.salt = null;
        } else {
            this.salt = value.getBytes(StandardCharsets.UTF_8);
        }
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * One model call ({@code llm}, {@code llm:vision}, {@code llm:embedding}), written by {@link CallLogModelGateway}.
     * Channel, guest and correlation come from {@link CallLogContext}; without one the source is {@code internal}.
     */
    public void llmCall(String callType, String model, String result, String errorCode, Integer tokensIn, Integer tokensOut, Long latencyMs) {
        if (!enabled()) {
            return;
        }
        try {
            CallLogContext.Caller caller = CallLogContext.current();
            append(new CallLogEntry(Instant.now(),
                    caller == null || blank(caller.source()) ? "internal" : lower(caller.source()),
                    caller == null ? null : pseudonym(caller.rawUserId()),
                    caller == null ? null : blankToNull(caller.correlationId()),
                    callType, blankToNull(model), nonBlank(result), blankToNull(errorCode),
                    tokensIn, tokensOut, tariff.cost(model, tokensIn, tokensOut), millis(latencyMs)));
        } catch (RuntimeException e) {
            log.warn("Call log model row was not written: {}", e.getClass().getSimpleName());
        }
    }

    /** One call to an external tool, e.g. {@code saby:/order/{id}}. */
    public void tool(String name, String result, String errorCode, Long latencyMs) {
        if (!enabled()) {
            return;
        }
        append(new CallLogEntry(Instant.now(), null, null, null, CallLogEntry.TYPE_TOOL, name, nonBlank(result),
                errorCode, null, null, null, millis(latencyMs)));
    }

    /** One message from a guest. */
    public void guestMessage(String source, String rawUserId, String correlationId) {
        if (!enabled()) {
            return;
        }
        append(new CallLogEntry(Instant.now(), lower(source), pseudonym(rawUserId), correlationId,
                CallLogEntry.TYPE_GUEST_MESSAGE, null, "received", null, null, null, null, null));
    }

    /** One target action: a reservation created, confirmed or seated, a bill opened. */
    public void targetAction(String name, String result, String source, String rawUserId) {
        if (!enabled()) {
            return;
        }
        append(new CallLogEntry(Instant.now(), lower(source), pseudonym(rawUserId), null,
                CallLogEntry.TYPE_TARGET_ACTION, name, nonBlank(result), null, null, null, null, null));
    }

    /** The first id that is present, as text: e.g. the Telegram user id, else the chat id. */
    public static String firstId(Long... ids) {
        for (Long id : ids) {
            if (id != null) {
                return id.toString();
            }
        }
        return null;
    }

    String pseudonym(String rawId) {
        if (salt == null || rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update((":" + rawId.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, PSEUDONYM_HEX);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private void append(CallLogEntry entry) {
        try {
            repository.append(entry);
        } catch (RuntimeException e) {
            log.warn("Call log row ({}) was not written: {}", entry.callType(), e.getClass().getSimpleName());
        }
    }

    /** The token usage of a model answer: {@code usage}, or {@code modelResponse.usage} of an audit record. */
    static Map<?, ?> usage(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        if (metadata.get("usage") instanceof Map<?, ?> usage && !usage.isEmpty()) {
            return usage;
        }
        if (metadata.get("modelResponse") instanceof Map<?, ?> response && response.get("usage") instanceof Map<?, ?> usage) {
            return usage;
        }
        return Map.of();
    }

    static Integer integer(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    // not a count
                }
            }
        }
        return null;
    }

    private static Integer millis(Long latencyMs) {
        return latencyMs == null ? null : (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs));
    }

    private static String lower(String value) {
        return blank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String value) {
        return blank(value) ? "unknown" : value.trim();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
