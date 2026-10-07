package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.domain.glasses.GlassesTranscriptFeed;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskFailure;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class GlassesFeedScopeTest {
    @Test void jsonAndPageRespectVenueAndStaffClaims() {
        var feed = new GlassesTranscriptFeed();
        add(feed, "AERIS", "alice", "own-answer");
        add(feed, "AERIS", "bob", "colleague-answer");
        add(feed, "OTHER", "alice", "foreign-answer");
        var controller = new GlassesFeedController(feed);
        var waiter = jwt("AERIS", "alice", "astor-waiter");
        assertThat(controller.json(waiter).getBody().toString()).contains("own-answer")
                .doesNotContain("colleague-answer", "foreign-answer");
        assertThat(controller.page(waiter).getBody().toString()).contains("own-answer")
                .doesNotContain("colleague-answer", "foreign-answer");
        var manager = jwt("AERIS", "manager", "astor-manager");
        assertThat(controller.json(manager).getBody().toString()).contains("own-answer", "colleague-answer")
                .doesNotContain("foreign-answer");
        assertThat(controller.page(manager).getBody().toString()).doesNotContain("foreign-answer");
    }
    @Test void noPrincipalNoVenueOrNoStaffRoleCannotReadTheFeed() {
        var controller = new GlassesFeedController(new GlassesTranscriptFeed());
        assertThatThrownBy(() -> controller.json(null)).isInstanceOf(StaffTaskFailure.class);
        assertThatThrownBy(() -> controller.page(jwt("", "alice", "astor-manager")))
                .isInstanceOf(StaffTaskFailure.class);
        assertThatThrownBy(() -> controller.json(jwt("AERIS", "alice", "ordinary-account")))
                .isInstanceOf(StaffTaskFailure.class);
    }
    private static Jwt jwt(String venue, String staff, String role) {
        return Jwt.withTokenValue("unit-test-only").header("alg", "RS256").subject(staff)
                .audience(List.of("astor-api")).claim("tenant", venue)
                .claim("realm_access", Map.of("roles", List.of(role))).build();
    }
    private static void add(GlassesTranscriptFeed feed, String venue, String staff, String answer) {
        feed.add(new GlassesTranscriptFeed.Entry(java.util.UUID.randomUUID().toString(), Instant.now().toString(),
                "text", staff, venue, null, null, "question", answer, false));
    }
}
