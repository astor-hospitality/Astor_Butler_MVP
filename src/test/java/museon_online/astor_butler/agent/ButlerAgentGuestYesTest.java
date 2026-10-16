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
import museon_online.astor_butler.capability.tools.ReservationAccess;
import museon_online.astor_butler.capability.tools.ReservationCancelTool;
import museon_online.astor_butler.capability.tools.ReservationChangeTool;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.anyMap;
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

    /** What a scripted model call costs, for the test with a daily budget. */
    private static final Map<String, Object> CHEAP = Map.of("costRub", 0.01);

    private Connection schema;
    private JdbcTemplate jdbc;
    private TableReservationService reservations;
    private final Deque<ModelChatResponse> script = new ArrayDeque<>();
    private AgentLoop loop;
    private ButlerAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-yes-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = dataSource.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-16-agent-budget.sql"));
        jdbc = new JdbcTemplate(dataSource);
        IdempotencyStore store = new IdempotencyStore(jdbc, new ObjectMapper());
        reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        when(reservations.cancelByGuest(42L)).thenReturn(CapabilityFixtures.order(42L, null, TableReservationStatus.CANCELLED));
        ReservationAccess access = mock(ReservationAccess.class);
        when(access.check(any(), anyMap())).thenReturn(new ReservationAccess.Checked(
                CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED), null));
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations), new FreeAtSeven(),
                new ReservationCancelTool(reservations, access),
                new ReservationChangeTool(reservations, access, new museon_online.astor_butler.domain.journey.GuestJourneyProperties())),
                mock(LedgerWriter.class), capabilities, store);
        when(reservations.changeByGuest(org.mockito.ArgumentMatchers.eq(42L), any())).thenReturn(
                CapabilityFixtures.order(42L, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
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
        loop = new AgentLoop(model, registry, properties);
        agent = new ButlerAgent(loop, registry, properties,
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
            assertThat(fact.get("facts")).isEqualTo(Map.of("reason", GuestAgreement.NEEDS_GUEST_YES, "date", "2026-10-20", "dateLabel", "вт 20.10",
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

    /**
     * The daily budget runs out on the offer: the guest's «да» gets no reservation for a model call, falls back and
     * books nothing, and the offer it never heard answered is withdrawn. The next venue day the venue asks again
     * before it books.
     */
    @Test
    void aSpentDailyBudgetStopsTheAgentAndTheYesWaitsForANewOffer() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-15T08:00:00Z"));
        // 3 RUB a day, 1 RUB held per call: the offer's answer costs 2.5, so the yes finds no room for a call.
        loop.setBudget(new AgentBudget(new BigDecimal("3"), BigDecimal.ONE, ZoneId.of("Asia/Yekaterinburg"), clock, jdbc));
        books();
        script.add(new ModelChatResponse("Могу забронировать на 20 октября, 19:00, на двоих. Бронирую?", Optional.empty(), "openai-compatible",
                "m", Duration.ZERO, Map.of("costRub", 2.5)));

        agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        // What the model would do on the yes, were it asked: book the offered table.
        books();
        says("Подтвердите, пожалуйста: 20 октября, 19:00, двое?");
        NegotiationReply spent = agent.negotiate(turn("Да", "m2"));
        assertThat(script).as("no model call on a spent budget").hasSize(2);
        clock.now = clock.now.plus(Duration.ofDays(1));
        NegotiationReply asked = agent.negotiate(turn("Да", "m3"));
        verify(reservations, never()).createReservation(any());
        books();
        says("Заявка передана хостес.");
        NegotiationReply booked = agent.negotiate(turn("Да", "m4"));

        assertThat(spent.fallback()).isTrue();
        assertThat(spent.facts()).isEmpty();
        assertThat(asked.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", false));
        verify(reservations, times(1)).createReservation(any());
        assertThat(booked.offer()).containsEntry("kind", "RESERVATION");
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

    @Test
    void aCancellationTheModelOffersWaitsForTheGuestsYes() {
        call("reservation_cancel", Map.of("reservationId", 42));
        says("Отменить бронь на 20 октября, 19:00?");
        call("reservation_cancel", Map.of("reservationId", 42));
        says("Бронь отменена.");

        NegotiationReply asked = agent.negotiate(turn("Планы поменялись", "m1"));
        verify(reservations, never()).cancelByGuest(any());
        agent.negotiate(turn("Да", "m2"));

        assertThat(asked.facts()).singleElement().satisfies(fact -> {
            Map<?, ?> refused = (Map<?, ?>) fact.get("facts");
            assertThat(refused.get("reason")).isEqualTo(GuestAgreement.NEEDS_GUEST_YES);
            assertThat(refused.get("action")).isEqualTo("cancel");
            assertThat(((Number) refused.get("reservationId")).longValue()).isEqualTo(42L);
        });
        verify(reservations, times(1)).cancelByGuest(42L);
    }

    /** Prod run 16.10: «нет, передумал, не надо» after an availability answer made the model call reservation_cancel. */
    @Test
    void aNoToTheOfferCancelsNothing() {
        call("table_availability", Map.of("date", "2026-10-20", "time", "19:00", "partySize", 2));
        says("На 19:00 есть стол у окна. Бронирую?");
        call("reservation_cancel", Map.of("reservationId", 42));
        says("Хорошо, отменил.");

        agent.negotiate(turn("Есть стол на 20-е в 19 на двоих?", "m1"));
        NegotiationReply no = agent.negotiate(turn("нет, передумал, не надо", "m2"));

        verify(reservations, never()).cancelByGuest(any());
        assertThat(no.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", false)
                .containsEntry("error", CapabilityResult.NOT_ALLOWED));
    }

    @Test
    void aChangeRunsOnlyOnTheYesToThatVeryChange() {
        Map<String, Object> toHalfEight = Map.of("reservationId", 42, "time", "20:30");
        Map<String, Object> toNine = Map.of("reservationId", 42, "time", "21:00");
        call("reservation_change", toHalfEight);
        says("Перенести бронь на 20:30?");
        call("reservation_change", toNine);
        says("Перенёс на 21:00.");
        call("reservation_change", toHalfEight);
        says("Перенести бронь на 20:30?");
        call("reservation_change", toHalfEight);
        says("Перенёс на 20:30, ждём подтверждения хостес.");

        agent.negotiate(turn("Можно позже?", "m1"));
        NegotiationReply otherTime = agent.negotiate(turn("да", "m2"));
        agent.negotiate(turn("Тогда в 20:30", "m3"));
        NegotiationReply changed = agent.negotiate(turn("да", "m4"));

        assertThat(otherTime.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", false));
        assertThat(changed.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("ok", true));
        verify(reservations, times(1)).changeByGuest(org.mockito.ArgumentMatchers.eq(42L), any());
    }

    @Test
    void theOfferedChangeIsReadFromTheTurnsToolFacts() {
        GuestAgreement.Change toHalfEight = new GuestAgreement.Change(42L, LocalDate.of(2026, 10, 21), LocalTime.of(20, 30), 3);
        AgentOutcome outcome = new AgentOutcome("Перенести?", null, false, 2, List.of(
                new AgentOutcome.ToolUse("reservation_change", GuestAgreement.refusal(42L, GuestAgreement.CHANGE,
                        toHalfEight.facts())),
                // Replayed from the idempotency store: numbers come back from JSON as any kind.
                new AgentOutcome.ToolUse("reservation_change", new CapabilityResult(false, Map.of("reason",
                        GuestAgreement.NEEDS_GUEST_YES, "reservationId", 43, "action", "change", "partySize", 4.0),
                        CapabilityResult.NOT_ALLOWED)),
                new AgentOutcome.ToolUse("reservation_cancel", GuestAgreement.refusal(44L, GuestAgreement.CANCEL, null))));

        assertThat(ButlerAgent.changes(outcome)).containsExactly(toHalfEight,
                new GuestAgreement.Change(43L, null, null, 4));
    }

    @Test
    void theGuestsOwnRequestCancelsAtOnce() {
        call("reservation_cancel", Map.of("reservationId", 42));
        says("Бронь отменена.");

        agent.negotiate(turn("Мы не придём, отмените бронь", "m1"));

        verify(reservations, times(1)).cancelByGuest(42L);
    }

    @Test
    void theOfferedCancellationIsReadFromTheTurnsToolFacts() {
        AgentOutcome outcome = new AgentOutcome("Отменить бронь 42?", null, false, 2, List.of(
                new AgentOutcome.ToolUse("reservation_cancel", GuestAgreement.refusal(42L, GuestAgreement.CANCEL, null)),
                new AgentOutcome.ToolUse("reservation_change", GuestAgreement.refusal(43L, GuestAgreement.CHANGE, null)),
                // Replayed from the idempotency store: the id comes back from JSON as an integer.
                new AgentOutcome.ToolUse("reservation_cancel", new CapabilityResult(false, Map.of("reason",
                        GuestAgreement.NEEDS_GUEST_YES, "reservationId", 44, "action", "cancel"), CapabilityResult.NOT_ALLOWED))));

        assertThat(ButlerAgent.cancellations(outcome)).containsExactly(42L, 44L);
    }

    private void books() {
        call("reservation_create", ReservationCreateToolTest.args());
    }

    private void call(String tool, Map<String, Object> args) {
        script.add(new ModelChatResponse("", Optional.of(new ModelToolCall(tool, args)), "gigachat", "m", Duration.ZERO, CHEAP));
    }

    private void says(String text) {
        script.add(new ModelChatResponse(text, Optional.empty(), "gigachat", "m", Duration.ZERO, CHEAP));
    }

    private static NegotiationTurn turn(String guestText, String turnId) {
        return new NegotiationTurn("c-yes", null, guestText, null, "Анна", null, null, turnId, true);
    }

    private static IncomingMessage message(String text) {
        return IncomingMessage.telegram(CapabilityFixtures.BUTLER_CHAT, CapabilityFixtures.BUTLER_CHAT, 1, 100, text, null, "Анна",
                null, "anna", "ru", false, "c");
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
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
