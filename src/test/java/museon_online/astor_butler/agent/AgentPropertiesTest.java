package museon_online.astor_butler.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    @Test
    void aNegotiationTurnEndsBeforeTheConciergeStopsWaiting() {
        // The Concierge waits BUTLER_NEGOTIATION_TIMEOUT_MS = 30 s for a turn.
        assertThat(new AgentProperties().getTurnTimeoutMs()).isEqualTo(25_000);
    }

    @Test
    void theAgentModelIsGigaChat2ProByDefault() {
        // Probed 14.10 on the PERS scope: GigaChat-3-Pro is «No such model», GigaChat-2-Pro answers with function_call.
        assertThat(new AgentProperties().getModel()).isEqualTo("GigaChat-2-Pro");
    }

    private static AgentProperties on(String allowlist) {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowlist(allowlist);
        return properties;
    }

    @Test
    void aStarAllowsEveryPrivateChat() {
        assertThat(on("*").allows(7L)).isTrue();
        assertThat(on("*").allows(123_456_789L)).isTrue();
    }

    @Test
    void allInAnyCaseAndSpacingAllowsEveryPrivateChat() {
        assertThat(on("all").allows(7L)).isTrue();
        assertThat(on(" ALL ").allows(7L)).isTrue();
    }

    @Test
    void aStarAmongIdsMeansEveryone() {
        assertThat(on("*,123").allows(999L)).isTrue();
    }

    @Test
    void anEmptyAllowlistStillMeansNobody() {
        assertThat(on("").allows(1L)).isFalse();
        assertThat(on(null).allows(1L)).isFalse();
    }

    @Test
    void idsAllowOnlyThemselves() {
        assertThat(on("1,2").allows(1L)).isTrue();
        assertThat(on("1,2").allows(3L)).isFalse();
    }

    @Test
    void groupChatsAreNeverTheAgentsEvenWithAStar() {
        // Guest agent turns are for private chats only; Telegram groups have negative ids.
        assertThat(on("*").allows(-1001234567890L)).isFalse();
        assertThat(on("all").allows(-5L)).isFalse();
        assertThat(on("-5").allows(-5L)).isFalse();
    }

    @Test
    void aStarDoesNotWorkWithTheAgentOff() {
        AgentProperties properties = on("*");
        properties.setEnabled(false);
        assertThat(properties.allows(7L)).isFalse();
    }
}
