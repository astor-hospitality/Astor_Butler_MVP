package museon_online.astor_butler.max;

import java.net.URI;
import java.time.Duration;

/**
 * Everything the MAX channel reads from the environment ({@code astor.max.*} in application.yaml).
 *
 * @param enabled             {@code ASTOR_MAX_ENABLED}; false (default) creates no polling thread and no MAX calls
 * @param token               {@code MAX_BOT_TOKEN} from business.max.ru; blank keeps the channel idle even when enabled
 * @param baseUrl             {@code MAX_API_BASE_URL}, {@code https://platform-api2.max.ru} (the host since 19.07.2026;
 *                            platform-api.max.ru is deprecated); reached directly, no proxy
 * @param caCertPath          {@code MAX_CA_CERT_PATH} (falls back to {@code GIGACHAT_CA_CERT_PATH}): PEM with the Russian
 *                            Trusted Root CA, which signs the MAX API certificate and is not in the JDK trust store
 * @param pollTimeout         long-poll wait of {@code GET /updates} (the API allows 0..90 s; kept within 1..90 s)
 * @param pollLimit           updates per poll (1..1000)
 * @param requestTimeout      every other call (send message, answer callback, /me)
 * @param retryDelay          pause after a failed poll before the next attempt
 * @param groupChatsEnabled   false (phase 1): only one-to-one dialogs with guests are routed, group chats are ignored
 * @param adminAlertsToTelegram admin alerts raised by MAX conversations go to the Telegram admin chat, as today
 */
public record MaxBotSettings(
        boolean enabled,
        String token,
        URI baseUrl,
        String caCertPath,
        Duration pollTimeout,
        int pollLimit,
        Duration requestTimeout,
        Duration retryDelay,
        boolean groupChatsEnabled,
        boolean adminAlertsToTelegram
) {

    public static final String DEFAULT_BASE_URL = "https://platform-api2.max.ru";

    public MaxBotSettings {
        token = token == null ? "" : token.trim();
        baseUrl = baseUrl == null ? URI.create(DEFAULT_BASE_URL) : baseUrl;
        caCertPath = caCertPath == null ? "" : caCertPath.trim();
        // At least 1 s: timeout=0 would turn long polling into a busy loop against the 30 rps limit.
        pollTimeout = clamp(pollTimeout, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(90));
        pollLimit = pollLimit <= 0 ? 100 : Math.min(pollLimit, 1000);
        requestTimeout = clamp(requestTimeout, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(60));
        retryDelay = clamp(retryDelay, Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofMinutes(5));
    }

    public static MaxBotSettings disabled() {
        return new MaxBotSettings(false, "", null, "", null, 0, null, null, false, true);
    }

    /** The channel only polls when it is switched on and has a token. */
    public boolean active() {
        return enabled && !token.isEmpty();
    }

    public static URI baseUrl(String value) {
        String url = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return URI.create(url);
    }

    private static Duration clamp(Duration value, Duration fallback, Duration min, Duration max) {
        if (value == null || value.isNegative()) {
            return fallback;
        }
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }
}
