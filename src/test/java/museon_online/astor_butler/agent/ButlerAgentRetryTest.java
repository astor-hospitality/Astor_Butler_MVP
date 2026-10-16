package museon_online.astor_butler.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.ReservationCreateToolTest;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelToolCall;
import museon_online.astor_butler.model.OpenAiCompatibleModelGateway;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.client.MockRestServiceServer;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** A Concierge retry of the same turn (same turnId) replays the booking instead of making a second one. */
class ButlerAgentRetryTest {

    private Connection schema;
    private IdempotencyStore store;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-retry-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = dataSource.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
        store = new IdempotencyStore(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = schema; var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test
    void theSameTurnIdBooksOnce() {
        TableReservationService reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations)),
                mock(LedgerWriter.class), capabilities, store);
        ToolCallingModel model = mock(ToolCallingModel.class);
        when(model.chat(any())).thenAnswer(invocation -> {
            ModelChatRequest request = invocation.getArgument(0);
            boolean called = request.messages().stream().anyMatch(message -> "function".equals(message.role()));
            // Says «передана» only after the booking ran: a claim no tool backs makes the turn fall back.
            boolean booked = request.messages().stream().anyMatch(message -> "function".equals(message.role())
                    && message.content() != null && message.content().contains("reservationId"));
            return called ? new ModelChatResponse(booked ? "Заявка передана хостес." : "Бронирую на 20 октября, 19:00?",
                    Optional.empty(), "gigachat", "m", Duration.ZERO)
                    : new ModelChatResponse("", Optional.of(new ModelToolCall("reservation_create", ReservationCreateToolTest.args())),
                    "gigachat", "m", Duration.ZERO);
        });
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        ButlerAgent agent = new ButlerAgent(new AgentLoop(model, registry, properties), registry, properties,
                mock(AgentLessonRepository.class), mock(GuestMemory.class), new VenueFactsCatalog(VenueFacts.none()), capabilities,
                mock(LedgerWriter.class), turns, mock(GuestVisitRepository.class));
        NegotiationTurn turn = new NegotiationTurn("c-1", null, "бронируйте", null, "Анна", null, "Бронируем?", "t-1", true);
        // The table is put to the guest first: a booking needs the guest's yes to the table offered just before.
        agent.negotiate(new NegotiationTurn("c-1", null, "Есть стол на 20-е в 19?", null, "Анна", null, null, "t-0", true));

        NegotiationReply first = agent.negotiate(turn);
        NegotiationReply retry = agent.negotiate(turn);

        verify(reservations, times(1)).createReservation(any());
        assertThat(first.offer()).containsEntry("kind", "RESERVATION");
        // The result is stored as JSON, so the replayed id comes back as a plain number.
        assertThat(((Number) first.offer().get("reservationId")).longValue()).isEqualTo(41L);
        assertThat(retry.offer()).containsEntry("reservationId", first.offer().get("reservationId"));
    }

    /**
     * The OpenAI-compatible provider booked, then failed before it could say so (429): the guest's outcome is unknown.
     * The turn falls back without another model call, and the Concierge's retry of the same turn replays the booking.
     * The booking is the guest's «бронируйте» to the table the venue put to them in the turn before.
     */
    @Test
    void aRetryAfterAnUnknownOutcomeOverTheOpenAiCompatibleApiBooksOnce() {
        TableReservationService reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations)),
                mock(LedgerWriter.class), capabilities, store);
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                "https://models.test/v1", "sk-fixture", "", "", "", "", 8000, 256, 0.1, true, "");
        MockRestServiceServer server = serverRef.get();
        String completions = "https://models.test/v1/chat/completions";
        String booking = """
                {"choices": [{"finish_reason": "tool_calls", "message": {"role": "assistant", "content": null, "tool_calls": [
                  {"id": "call_1", "type": "function", "function": {"name": "reservation_create",
                   "arguments": "{\\"date\\": \\"2026-10-20\\", \\"time\\": \\"19:00\\", \\"partySize\\": 2, \\"guestName\\": \\"Анна Петрова\\"}"}}
                ]}}], "usage": {"prompt_tokens": 100, "completion_tokens": 20}}
                """;
        // The offer: booking before the guest's yes is refused, and the venue asks.
        server.expect(once(), requestTo(completions)).andRespond(withSuccess(booking, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(completions)).andRespond(withSuccess("""
                {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "Бронирую на 20 октября, 19:00, на двоих?"}}]}
                """, MediaType.APPLICATION_JSON));
        // The guest's yes: booked, then 429 before the answer; the retry replays the booking and answers.
        server.expect(once(), requestTo(completions)).andRespond(withSuccess(booking, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(completions)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(completions)).andRespond(withSuccess(booking, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(completions)).andRespond(withSuccess("""
                {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "Заявка передана хостес."}}]}
                """, MediaType.APPLICATION_JSON));
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setModel("gigachat-3-pro");
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        ButlerAgent agent = new ButlerAgent(new AgentLoop(gateway, registry, properties), registry, properties,
                mock(AgentLessonRepository.class), mock(GuestMemory.class), new VenueFactsCatalog(VenueFacts.none()), capabilities,
                mock(LedgerWriter.class), turns, mock(GuestVisitRepository.class));
        NegotiationTurn turn = new NegotiationTurn("c-2", null, "бронируйте", null, "Анна", null, "Бронируем?", "t-7", true);
        NegotiationReply offered = agent.negotiate(new NegotiationTurn("c-2", null, "Есть стол на 20-е в 19 на двоих?", null, "Анна",
                null, null, "t-6", true));

        NegotiationReply unknown = agent.negotiate(turn);
        NegotiationReply retry = agent.negotiate(turn);

        server.verify();
        assertThat(offered.fallback()).isFalse();
        assertThat(offered.offer()).isNull();
        assertThat(unknown.fallback()).isTrue();
        assertThat(retry.fallback()).isFalse();
        verify(reservations, times(1)).createReservation(any());
        assertThat(((Number) retry.offer().get("reservationId")).longValue()).isEqualTo(41L);
    }
}
