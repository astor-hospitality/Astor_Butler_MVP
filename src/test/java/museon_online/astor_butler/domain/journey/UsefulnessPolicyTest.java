package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsefulnessPolicyTest {

    private static final long CHAT = 421441838L;

    private JourneyTestDatabase database;
    private GuestVisitRepository repository;
    private UsefulnessPolicyProperties properties;
    private UsefulnessPolicy policy;

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        repository = new GuestVisitRepository(database.jdbc());
        properties = new UsefulnessPolicyProperties();
        properties.setEnabled(true);
        policy = new UsefulnessPolicy(repository, properties);
        repository.open(42L, CHAT, "+79120000000", "AERIS", Instant.parse("2026-10-14T08:00:00Z"));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void allowsWhitelistedKindOnce() {
        assertThat(policy.allow(42L, CHAT, ProactiveMessage.MORNING)).isInstanceOf(ProactiveDecision.Allow.class);
        assertThat(policy.allow(42L, CHAT, ProactiveMessage.MORNING)).isEqualTo(new ProactiveDecision.Silent("already sent"));
        assertThat(policy.allow(42L, CHAT, ProactiveMessage.CONFIRMED)).isInstanceOf(ProactiveDecision.Allow.class);
    }

    @Test
    void staffComingAllowedPerRequest() {
        assertThat(policy.allow(42L, CHAT, ProactiveMessage.STAFF_COMING)).isInstanceOf(ProactiveDecision.Allow.class);
        assertThat(policy.allow(42L, CHAT, ProactiveMessage.STAFF_COMING)).isInstanceOf(ProactiveDecision.Allow.class);
    }

    @Test
    void silentWhenOptedOut() {
        repository.optOut(CHAT);

        assertThat(policy.allow(42L, CHAT, ProactiveMessage.MORNING)).isEqualTo(new ProactiveDecision.Silent("opted out"));
    }

    @Test
    void silentWhenPolicyFails() {
        GuestVisitRepository broken = mock(GuestVisitRepository.class);
        when(broken.optedOut(anyLong())).thenThrow(new IllegalStateException("db down"));

        assertThat(new UsefulnessPolicy(broken, properties).allow(42L, CHAT, ProactiveMessage.MORNING))
                .isEqualTo(new ProactiveDecision.Silent("error"));
    }

    @Test
    void flagOffAlwaysAllows() {
        GuestVisitRepository untouched = mock(GuestVisitRepository.class);
        properties.setEnabled(false);

        assertThat(new UsefulnessPolicy(untouched, properties).allow(42L, CHAT, ProactiveMessage.MORNING))
                .isInstanceOf(ProactiveDecision.Allow.class);
        verifyNoInteractions(untouched);
    }

    @Test
    void withoutAVisitTheKindIsNotDeduplicatedButOptOutHolds() {
        assertThat(policy.allow(99L, CHAT, ProactiveMessage.CONFIRMED)).isInstanceOf(ProactiveDecision.Allow.class);
        assertThat(policy.allow(99L, CHAT, ProactiveMessage.CONFIRMED)).isInstanceOf(ProactiveDecision.Allow.class);
        repository.optOut(CHAT);
        assertThat(policy.allow(99L, CHAT, ProactiveMessage.CONFIRMED)).isInstanceOf(ProactiveDecision.Silent.class);
    }
}
