package museon_online.astor_butler.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.ReservationCreateToolTest;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
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
import museon_online.astor_butler.model.ModelUnavailableException;
import museon_online.astor_butler.model.ToolCallingModel;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A table request needs the guest's own «да» to the table the venue offered in its previous answer, whatever the model
 * decides: the model is scripted to book too early, and the tool refuses until the guest has agreed.
 */
class ButlerAgentGuestYesTest {

    private Connection schema;
    private TableReservationService reservations;
    private final Deque<ModelChatResponse> script = new ArrayDeque<>();
    private ButlerAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-yes-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = dataSource.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
        IdempotencyStore store = new IdempotencyStore(new JdbcTemplate(dataSource), new ObjectMapper());
        reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations), new FreeAtSeven()),
                mock(LedgerWriter.class), capabilities, store);
        ToolCallingModel model = request -> {
            ModelChatResponse next = script.poll();
            if (next == null) {
                throw new ModelUnavailableException("script is over", null);
            }
            return next;
        };
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowlist(String.valueOf(CapabilityFixtures.BUTLER_CHAT));
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        when(visits.activeByChat(anyLong(), any())).thenReturn(Optional.empty());
        agent = new ButlerAgent(new AgentLoop(model, registry, properties), registry, properties,
                mock(AgentLessonRepository.class), mock(GuestMemory.class), new VenueFactsCatalog(VenueFacts.none()), capabilities,
                mock(LedgerWriter.class), turns, visits);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = schema; var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test
    void aBookingBeforeTheGuestsYesIsRefusedWithTheTableToAskAbout() {
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");

        NegotiationReply reply = agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));

        verify(reservations, never()).createReservation(any());
        assertThat(reply.fallback()).isFalse();
        assertThat(reply.offer()).isNull();
        assertThat(reply.facts()).singleElement().satisfies(fact -> {
            assertThat(fact).containsEntry("tool", "reservation_create").containsEntry("ok", false)
                    .containsEntry("error", CapabilityResult.NOT_ALLOWED);
            assertThat(fact.get("facts")).isEqualTo(Map.of("reason", GuestAgreement.NEEDS_GUEST_YES, "date", "2026-10-20",
                    "time", "19:00", "partySize", 2));
        });
    }

    @Test
    void theGuestsYesToTheRefusedTableBooksIt() {
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");
        books();
        says("Заявка передана хостес.");

        agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        NegotiationReply booked = agent.negotiate(turn("Да, бронируйте", "m2"));

        ArgumentCaptor<TableReservationCommand> command = ArgumentCaptor.forClass(TableReservationCommand.class);
        verify(reservations, times(1)).createReservation(command.capture());
        assertThat(command.getValue().channel()).isEqualTo(TableReservationCommand.CHANNEL_CONCIERGE);
        assertThat(booked.offer()).containsEntry("kind", "RESERVATION").containsEntry("status", "AWAITING_HOSTESS");
    }

    @Test
    void theGuestsYesToAFreeTimeTheVenueFoundBooksIt() {
        call("table_availability", Map.of("date", "2026-10-20", "time", "19:00", "partySize", 2));
        says("На 19:00 есть стол у окна. Бронирую?");
        books();
        says("Заявка передана хостес.");

        agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        NegotiationReply booked = agent.negotiate(turn("ок", "m2"));

        verify(reservations, times(1)).createReservation(any());
        assertThat(booked.offer()).containsEntry("kind", "RESERVATION");
    }

    @Test
    void aYesIsOnlyForTheTableOfferedJustBefore() {
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");
        says("Парковка во дворе.");
        books();
        says("Подтвердите, пожалуйста: 20 октября, 19:00, двое?");
        Map<String, Object> eight = ReservationCreateToolTest.args();
        eight.put("time", "20:00");
        call("reservation_create", eight);
        says("Подтвердите 20:00?");

        agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        agent.negotiate(turn("А парковка есть?", "m2"));
        NegotiationReply stale = agent.negotiate(turn("Да", "m3"));
        NegotiationReply otherTime = agent.negotiate(turn("Да, но лучше в 20:00", "m4"));

        verify(reservations, never()).createReservation(any());
        assertThat(stale.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", false));
        assertThat(otherTime.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", false));
    }

    @Test
    void aTurnThatFellBackWithdrawsTheOffer() {
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");

        agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        // The script is over: the model fails and the turn falls back, so the guest never heard the venue's answer.
        NegotiationReply fallback = agent.negotiate(turn("А у окна можно?", "m2"));
        books();
        says("Подтвердите, пожалуйста: 20 октября, 19:00, двое?");
        NegotiationReply after = agent.negotiate(turn("да", "m3"));

        assertThat(fallback.fallback()).isTrue();
        verify(reservations, never()).createReservation(any());
        assertThat(after.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", false));
    }

    @Test
    void aRetryOfTheSameTurnIdReplaysItsAnswer() {
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");
        books();
        says("Заявка передана хостес.");
        books();
        says("Заявка передана хостес.");

        NegotiationReply refused = agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        NegotiationReply refusedAgain = agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        NegotiationReply booked = agent.negotiate(turn("да", "m2"));
        NegotiationReply retried = agent.negotiate(turn("да", "m2"));

        verify(reservations, times(1)).createReservation(any());
        assertThat(refusedAgain.facts()).isEqualTo(refused.facts());
        assertThat(((Number) booked.offer().get("reservationId")).longValue()).isEqualTo(41L);
        assertThat(retried.offer()).containsEntry("reservationId", booked.offer().get("reservationId"));
    }

    @Test
    void theButlersOwnBotWaitsForTheSameYes() {
        books();
        says("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");
        books();
        says("Заявка передана хостес.");

        Optional<String> asked = agent.reply(message("Стол на 20-е в 19 на двоих"), null);
        verify(reservations, never()).createReservation(any());
        Optional<String> booked = agent.reply(message("Да"), null);

        assertThat(asked).contains("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?");
        assertThat(booked).contains("Заявка передана хостес.");
        ArgumentCaptor<TableReservationCommand> command = ArgumentCaptor.forClass(TableReservationCommand.class);
        verify(reservations, times(1)).createReservation(command.capture());
        assertThat(command.getValue().channel()).isEqualTo(TableReservationCommand.CHANNEL_TELEGRAM);
        assertThat(command.getValue().chatId()).isEqualTo(CapabilityFixtures.BUTLER_CHAT);
    }

    @Test
    void theOfferIsReadFromTheTurnsToolFacts() {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("date", "2026-10-20");
        full.put("time", "19:00");
        full.put("partySize", 2);
        full.put("open", true);
        full.put("free", List.of());
        full.put("alternatives", List.of(Map.of("time", "19:15", "tableCode", "T2"), Map.of("time", "19:45", "tableCode", "T3")));
        GuestAgreement.Table forThree = new GuestAgreement.Table(LocalDate.of(2026, 10, 20), LocalTime.of(20, 0), 3);
        AgentOutcome outcome = new AgentOutcome("Свободно в 19:15 или 19:45.", null, false, 3, List.of(
                new AgentOutcome.ToolUse("table_availability", CapabilityResult.ok(full)),
                new AgentOutcome.ToolUse("reservation_create", new CapabilityResult(false, Map.of("reason", "table_busy"),
                        CapabilityResult.NOT_ALLOWED)),
                new AgentOutcome.ToolUse("reservation_create", GuestAgreement.refusal(forThree))));

        assertThat(ButlerAgent.proposed(outcome)).containsExactly(
                new GuestAgreement.Table(LocalDate.of(2026, 10, 20), LocalTime.of(19, 15), 2),
                new GuestAgreement.Table(LocalDate.of(2026, 10, 20), LocalTime.of(19, 45), 2),
                forThree);
    }

    private void books() {
        call("reservation_create", ReservationCreateToolTest.args());
    }

    private void call(String tool, Map<String, Object> args) {
        script.add(new ModelChatResponse("", Optional.of(new ModelToolCall(tool, args)), "gigachat", "m", Duration.ZERO));
    }

    private void says(String text) {
        script.add(new ModelChatResponse(text, Optional.empty(), "gigachat", "m", Duration.ZERO));
    }

    private static NegotiationTurn turn(String guestText, String turnId) {
        return new NegotiationTurn("c-yes", null, guestText, null, "Анна", null, null, turnId, true);
    }

    private static IncomingMessage message(String text) {
        return IncomingMessage.telegram(CapabilityFixtures.BUTLER_CHAT, CapabilityFixtures.BUTLER_CHAT, 1, 100, text, null, "Анна",
                null, "anna", "ru", false, "c");
    }

    /** Availability as the real tool reports it: a free table at 19:00 on 20.10 for two. */
    private static final class FreeAtSeven implements CapabilityTool {
        @Override
        public String name() {
            return "table_availability";
        }

        @Override
        public String description() {
            return "Свободные столы";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public boolean commitment() {
            return false;
        }

        @Override
        public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("date", "2026-10-20");
            facts.put("time", "19:00");
            facts.put("partySize", 2);
            facts.put("open", true);
            facts.put("free", List.of(Map.of("tableCode", "T5", "name", "Стол у окна", "capacity", 4)));
            facts.put("alternatives", List.of());
            return CapabilityResult.ok(facts);
        }
    }
}
