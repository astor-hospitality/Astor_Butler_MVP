package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class GlassesReplyCacheTest {
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("venue", "staff");

    @Test void scopeAndCanonicalUuidProtectCachedReply() {
        var cache = new GlassesReplyCache();
        cache.remember(scope, ID, "input", "private reply");
        assertThat(cache.find(scope, ID.toUpperCase(), "input")).isEqualTo("private reply");
        assertThat(cache.find(new GlassesAccess.Scope("other", "staff"), ID, "input")).isNull();
        assertThat(cache.find(new GlassesAccess.Scope("venue", "other"), ID, "input")).isNull();
        assertThatThrownBy(() -> cache.find(scope, ID, "changed")).satisfies(e ->
                assertThat(((GlassesFailure)e).code).isEqualTo("REQUEST_ID_CONFLICT"));
    }

    @Test void capacityAndTtlBoundRetention() {
        var now = new MutableClock();
        var cache = new GlassesReplyCache(now, 1, Duration.ofSeconds(120));
        cache.remember(scope, ID, "a", "old");
        String next = "dfc35898-8eb3-407c-a65e-8e1cb50f2642";
        cache.remember(scope, next, "b", "new");
        assertThat(cache.find(scope, ID, "a")).isNull();
        now.value = now.value.plusSeconds(120);
        assertThat(cache.find(scope, next, "b")).isNull();
    }

    @Test void fingerprintDistinguishesMediaAndUnambiguousText() {
        assertThat(GlassesReplyCache.fingerprint("audio", "", new byte[]{1}))
                .isNotEqualTo(GlassesReplyCache.fingerprint("image", "", new byte[]{1}))
                .isNotEqualTo(GlassesReplyCache.fingerprint("audio", "", new byte[]{2}));
        assertThat(GlassesReplyCache.digest(new byte[]{1}, new byte[]{2,3}))
                .isNotEqualTo(GlassesReplyCache.digest(new byte[]{1,2}, new byte[]{3}));
    }

    private static class MutableClock extends Clock {
        Instant value = Instant.parse("2026-10-04T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
