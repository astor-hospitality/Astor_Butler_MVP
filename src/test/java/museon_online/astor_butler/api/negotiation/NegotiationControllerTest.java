package museon_online.astor_butler.api.negotiation;

import museon_online.astor_butler.agent.ButlerAgent;
import museon_online.astor_butler.agent.NegotiationReply;
import museon_online.astor_butler.agent.NegotiationTurn;
import museon_online.astor_butler.config.InternalApiGuardFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NegotiationControllerTest {

    private static final String TOKEN = "unit-test-internal-token-not-a-real-credential";
    private static final String BODY = """
            {"sessionId":"c-1","visitId":null,"guestText":"стол на двоих в 13:00",
             "conciergePosition":{"time":"13:00","partySize":2},"guestName":"Мария","guestPhone":null,
             "conciergeMessage":"Гостья хочет обед на двоих.","turnId":"t-1","guestConsent":true}
            """;

    private ButlerAgent agent;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        agent = mock(ButlerAgent.class);
        mvc = MockMvcBuilders.standaloneSetup(new NegotiationController(agent))
                .addFilters(new InternalApiGuardFilter(TOKEN))
                .build();
    }

    @Test
    void requiresTheInternalToken() throws Exception {
        mvc.perform(post("/api/negotiation/v1/turn").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/negotiation/v1/turn").header(InternalApiGuardFilter.HEADER, TOKEN + "x")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        verify(agent, never()).negotiate(any());
    }

    @Test
    void returnsTheOfferAndFacts() throws Exception {
        when(agent.negotiate(any())).thenReturn(new NegotiationReply("В 13:00 занято, есть 13:30 у окна.",
                Map.of("kind", "ALTERNATIVE_TIME", "time", "13:30"),
                List.of(Map.of("tool", "table_availability", "ok", true)), 42L, false));

        mvc.perform(post("/api/negotiation/v1/turn").header(InternalApiGuardFilter.HEADER, TOKEN)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.butlerText").value("В 13:00 занято, есть 13:30 у окна."))
                .andExpect(jsonPath("$.offer.kind").value("ALTERNATIVE_TIME"))
                .andExpect(jsonPath("$.offer.time").value("13:30"))
                .andExpect(jsonPath("$.facts[0].tool").value("table_availability"))
                .andExpect(jsonPath("$.visitId").value(42))
                .andExpect(jsonPath("$.fallback").value(false));

        ArgumentCaptor<NegotiationTurn> turn = ArgumentCaptor.forClass(NegotiationTurn.class);
        verify(agent).negotiate(turn.capture());
        assertThat(turn.getValue().sessionId()).isEqualTo("c-1");
        assertThat(turn.getValue().conciergeMessage()).isEqualTo("Гостья хочет обед на двоих.");
        assertThat(turn.getValue().conciergePosition()).containsEntry("partySize", 2);
        assertThat(turn.getValue().turnId()).isEqualTo("t-1");
        assertThat(turn.getValue().guestConsent()).isTrue();
    }

    @Test
    void theGuestsTelegramUserIdIsOptional() throws Exception {
        when(agent.negotiate(any())).thenReturn(new NegotiationReply("Отменили.", null, List.of(), null, false));
        String named = BODY.replace("\"guestConsent\":true}", "\"guestConsent\":true,\"guestTelegramUserId\":777001}");

        mvc.perform(post("/api/negotiation/v1/turn").header(InternalApiGuardFilter.HEADER, TOKEN)
                        .contentType("application/json").content(named))
                .andExpect(status().isOk());
        mvc.perform(post("/api/negotiation/v1/turn").header(InternalApiGuardFilter.HEADER, TOKEN)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<NegotiationTurn> turn = ArgumentCaptor.forClass(NegotiationTurn.class);
        verify(agent, org.mockito.Mockito.times(2)).negotiate(turn.capture());
        assertThat(turn.getAllValues().get(0).guestTelegramUserId()).isEqualTo(777001L);
        assertThat(turn.getAllValues().get(0).guestConsent()).isTrue();
        assertThat(turn.getAllValues().get(1).guestTelegramUserId()).isNull();
    }

    @Test
    void aTurnWithoutSessionIsABadRequest() throws Exception {
        mvc.perform(post("/api/negotiation/v1/turn").header(InternalApiGuardFilter.HEADER, TOKEN)
                        .contentType("application/json").content("{\"guestText\":\"стол\"}"))
                .andExpect(status().isBadRequest());
        verify(agent, never()).negotiate(any());
    }

    @Test
    void theTurnRunsUnderAConciergeCallLogContext() throws Exception {
        java.util.concurrent.atomic.AtomicReference<museon_online.astor_butler.domain.calllog.CallLogContext.Caller> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(agent.negotiate(any())).thenAnswer(invocation -> {
            seen.set(museon_online.astor_butler.domain.calllog.CallLogContext.current());
            return new NegotiationReply("Ок.", null, List.of(), null, false);
        });

        mvc.perform(post("/api/negotiation/v1/turn").header(InternalApiGuardFilter.HEADER, TOKEN)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().source()).isEqualTo("concierge");
        assertThat(seen.get().rawUserId()).isEqualTo("c-1");
        assertThat(seen.get().correlationId()).isEqualTo("t-1");
        assertThat(museon_online.astor_butler.domain.calllog.CallLogContext.current()).isNull();
    }
}
