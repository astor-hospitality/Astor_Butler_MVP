package museon_online.astor_butler.domain.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rules for the anonymous WEB channel: which sites talk to the guest FSM instead of the lead fast path,
 * and how a browser request is reduced to a stable web identity. The browser never chooses a chatId or
 * an externalUserId; both derive from {@code payload.sessionId} only.
 */
@Component
public class WebChannelPolicy {

    public static final String SESSION_ID_PREFIX = "web-";
    public static final String ANON_PREFIX = "web:anon:";
    public static final int MAX_TEXT_CHARS = 4000;

    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{5,79}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()-]{6,24}$");

    private final Set<String> fsmSites;
    private final String defaultSite;

    public WebChannelPolicy(
            @Value("${astor.web.fsm-sites:astor-butler-commercial,astor-butler,astor}") String fsmSites,
            @Value("${astor.web.default-site:c3ag}") String defaultSite
    ) {
        this.fsmSites = Arrays.stream(fsmSites == null ? new String[0] : fsmSites.split(","))
                .map(String::trim)
                .filter(site -> !site.isBlank())
                .map(site -> site.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.defaultSite = defaultSite == null || defaultSite.isBlank() ? "c3ag" : defaultSite.trim().toLowerCase(Locale.ROOT);
    }

    /** Sites whose web chat runs the same guest FSM as Telegram; everything else keeps the C3AG lead fast path. */
    public boolean routesToFsm(String site) {
        return site != null && fsmSites.contains(site.trim().toLowerCase(Locale.ROOT));
    }

    public String site(Map<String, Object> payload) {
        Object site = payload == null ? null : payload.get("site");
        String value = site == null ? "" : site.toString().trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? defaultSite : value;
    }

    /** Reduces a browser request to what the gateway may trust. */
    public WebInbound sanitize(String externalUserId, String text, String contactPhone, Map<String, Object> payload) {
        Map<String, Object> safePayload = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        String sessionId = sessionId(safePayload.get("sessionId"));
        boolean generated = sessionId == null;
        if (generated) {
            sessionId = newSessionId();
        }
        safePayload.put("sessionId", sessionId);
        safePayload.putIfAbsent("site", defaultSite);

        String safeExternalUserId = externalUserId != null && externalUserId.trim().startsWith(ANON_PREFIX)
                ? externalUserId.trim()
                : null;

        String safeText = text == null ? "" : text.strip();
        if (safeText.length() > MAX_TEXT_CHARS) {
            safeText = safeText.substring(0, MAX_TEXT_CHARS);
        }
        Object action = safePayload.get("action");
        if (safeText.isBlank() && action != null && !action.toString().isBlank()) {
            // A pressed quick reply stands for words the guest could have typed.
            safeText = action.toString().strip();
        }

        String phone = firstPhone(contactPhone, safePayload.get("contactPhone"));
        safePayload.remove("contactPhone");
        // Server-side only: never let the browser pick the key its budgets are counted under.
        safePayload.remove(WebModelBudget.CLIENT_KEY);

        return new WebInbound(sessionId, generated, safeExternalUserId, safeText, phone, Map.copyOf(safePayload));
    }

    /** Adds the hashed client address the per-IP budgets are keyed by; the raw address is not stored. */
    public Map<String, Object> withClientKey(Map<String, Object> payload, String clientIp) {
        Map<String, Object> keyed = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        keyed.put(WebModelBudget.CLIENT_KEY, clientKey(clientIp));
        return Map.copyOf(keyed);
    }

    public static String clientKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "ip:unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(clientIp.trim().getBytes(StandardCharsets.UTF_8));
            return "ip:" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sessionId(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return SESSION_ID.matcher(value).matches() ? value : null;
    }

    public String newSessionId() {
        return SESSION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private String firstPhone(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String value = candidate.toString().trim();
            if (!value.isBlank() && PHONE.matcher(value).matches()) {
                return value;
            }
        }
        return null;
    }

    public record WebInbound(
            String sessionId,
            boolean sessionGenerated,
            String externalUserId,
            String text,
            String contactPhone,
            Map<String, Object> payload
    ) {
    }
}
