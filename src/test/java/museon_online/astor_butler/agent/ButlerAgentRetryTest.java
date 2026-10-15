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
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            return called ? new ModelChatResponse("Заявка передана хостес.", Optional.empty(), "gigachat", "m", Duration.ZERO)
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
}
