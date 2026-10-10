package museon_online.astor_butler.domain.web;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebChannelPolicyTest {

    private final WebChannelPolicy policy = new WebChannelPolicy(" Astor-Butler-Commercial , astor ", "c3ag");

    @Test
    void routesOnlyConfiguredSitesToTheFsm() {
        assertThat(policy.routesToFsm("astor-butler-commercial")).isTrue();
        assertThat(policy.routesToFsm("ASTOR")).isTrue();
        assertThat(policy.routesToFsm("c3ag")).isFalse();
        assertThat(policy.routesToFsm(null)).isFalse();
        assertThat(policy.site(Map.of())).isEqualTo("c3ag");
        assertThat(policy.site(Map.of("site", " Astor "))).isEqualTo("astor");
    }

    @Test
    void sanitizeDropsBrowserChosenIdentityKeepsAnonymousOneAndEnsuresSession() {
        WebChannelPolicy.WebInbound inbound = policy.sanitize("staff:1", "  Привет  ", null,
                Map.of("sessionId", "web-abc-123", "site", "astor"));
        assertThat(inbound.sessionId()).isEqualTo("web-abc-123");
        assertThat(inbound.sessionGenerated()).isFalse();
        assertThat(inbound.externalUserId()).isNull();
        assertThat(inbound.text()).isEqualTo("Привет");
        assertThat(inbound.contactPhone()).isNull();

        assertThat(policy.sanitize("web:anon:web-abc-123", "x", null, Map.of("sessionId", "web-abc-123")).externalUserId())
                .isEqualTo("web:anon:web-abc-123");
    }

    @Test
    void invalidOrMissingSessionIdsAreReplacedByAGeneratedOne() {
        for (Object bad : new Object[]{null, "", "short", "../etc/passwd", "a b", "x".repeat(81), "{\"$ne\":1}"}) {
            Map<String, Object> payload = new HashMap<>();
            if (bad != null) {
                payload.put("sessionId", bad);
            }
            WebChannelPolicy.WebInbound inbound = policy.sanitize(null, "hi", null, payload);
            assertThat(inbound.sessionGenerated()).as(String.valueOf(bad)).isTrue();
            assertThat(inbound.sessionId()).startsWith("web-").hasSizeGreaterThan(20);
            assertThat(inbound.payload().get("sessionId")).isEqualTo(inbound.sessionId());
            assertThat(inbound.payload().get("site")).isEqualTo("c3ag");
        }
    }

    @Test
    void actionFillsBlankTextAndPhoneComesFromEitherPlaceButLeavesThePayload() {
        WebChannelPolicy.WebInbound action = policy.sanitize(null, "", null, Map.of("sessionId", "web-abc-123", "action", "Бронь стола"));
        assertThat(action.text()).isEqualTo("Бронь стола");

        WebChannelPolicy.WebInbound fromPayload = policy.sanitize(null, "", null, Map.of("sessionId", "web-abc-123", "contactPhone", "+7 (900) 000-00-00"));
        assertThat(fromPayload.contactPhone()).isEqualTo("+7 (900) 000-00-00");
        assertThat(fromPayload.payload()).doesNotContainKey("contactPhone");

        WebChannelPolicy.WebInbound fromField = policy.sanitize(null, "", "89000000000", Map.of("sessionId", "web-abc-123"));
        assertThat(fromField.contactPhone()).isEqualTo("89000000000");

        assertThat(policy.sanitize(null, "", null, Map.of("sessionId", "web-abc-123", "contactPhone", "not a phone")).contactPhone()).isNull();
        assertThat(policy.sanitize(null, "y".repeat(5000), null, Map.of("sessionId", "web-abc-123")).text()).hasSize(WebChannelPolicy.MAX_TEXT_CHARS);
    }
}
