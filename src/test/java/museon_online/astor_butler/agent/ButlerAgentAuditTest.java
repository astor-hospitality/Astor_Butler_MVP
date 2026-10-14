package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.GigaChatModelGateway;
import museon_online.astor_butler.model.ModelInteractionAuditRecord;
import museon_online.astor_butler.model.ModelInteractionAuditRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Without the guest's consent the model audit keeps the shape of a negotiation turn, never its words. */
class ButlerAgentAuditTest {

    private static final String OAUTH = "https://ngw.test:9443/api/v2/oauth";
    private static final String BASE_URL = "https://gigachat.test/api/v1";
    private static final String GUEST_WORDS = "у меня аллергия на орехи, приду с мамой";
    private static final String CONCIERGE_WORDS = "Гостья Мария просит тихий стол";
    private static final String ANSWER = "Есть тихий стол у окна в 13:00.";

    private ModelInteractionAuditRecord negotiate(Boolean consent) {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        GigaChatModelGateway gateway = new GigaChatModelGateway(
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                OAUTH, BASE_URL, "YmFzZTY0LWZpeHR1cmU=", "GIGACHAT_API_PERS", "GigaChat", "", "", "Embeddings",
                "", 8000, 128, 128, 0.1);
        MockRestServiceServer server = serverRef.get();
        server.expect(once(), requestTo(OAUTH)).andRespond(withSuccess(
                "{\"access_token\": \"tok-1\", \"expires_at\": " + (System.currentTimeMillis() + 1_800_000) + "}",
                MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/chat/completions")).andRespond(withSuccess(
                "{\"choices\": [{\"finish_reason\": \"stop\", \"message\": {\"role\": \"assistant\", \"content\": \"" + ANSWER + "\"}}],"
                        + " \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}", MediaType.APPLICATION_JSON));
        ModelInteractionAuditRepository audit = mock(ModelInteractionAuditRepository.class);
        gateway.setAuditRepository(audit);

        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        LedgerWriter ledger = mock(LedgerWriter.class);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(), ledger, capabilities);
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        ButlerAgent agent = new ButlerAgent(new AgentLoop(gateway, registry, properties), registry, properties,
                mock(AgentLessonRepository.class), mock(GuestMemory.class),
                new VenueFactsCatalog(new VenueFacts(null, null, null, null, null)), capabilities, ledger, turns,
                mock(GuestVisitRepository.class));

        NegotiationReply reply = agent.negotiate(new NegotiationTurn("c-1", null, GUEST_WORDS, Map.of("time", "13:00"),
                "Мария", null, CONCIERGE_WORDS, "t-1", consent));

        assertThat(reply.fallback()).isFalse();
        assertThat(reply.butlerText()).isEqualTo(ANSWER);
        ArgumentCaptor<ModelInteractionAuditRecord> record = ArgumentCaptor.forClass(ModelInteractionAuditRecord.class);
        verify(audit).capture(record.capture());
        return record.getValue();
    }

    @Test
    void withoutConsentTheAuditKeepsOnlyRoles() {
        ModelInteractionAuditRecord record = negotiate(null);

        assertThat(record.prompt()).contains("system: [скрыто]").contains("user: [скрыто]")
                .doesNotContain("орехи").doesNotContain("тихий").doesNotContain("Мария").doesNotContain("Батлер");
        assertThat(record.responseText()).doesNotContain("тихий");
        assertThat(record.guestText()).isEmpty();
    }

    @Test
    void withConsentTheAuditKeepsTheConversation() {
        ModelInteractionAuditRecord record = negotiate(true);

        assertThat(record.prompt()).contains("орехи").contains(CONCIERGE_WORDS);
        assertThat(record.responseText()).isEqualTo(ANSWER);
    }
}
