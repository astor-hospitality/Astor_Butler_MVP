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
}
