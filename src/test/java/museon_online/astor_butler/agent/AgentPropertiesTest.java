package museon_online.astor_butler.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    @Test
    void aNegotiationTurnEndsBeforeTheConciergeStopsWaiting() {
        // The Concierge waits BUTLER_NEGOTIATION_TIMEOUT_MS = 30 s for a turn.
        assertThat(new AgentProperties().getTurnTimeoutMs()).isEqualTo(25_000);
    }
}
