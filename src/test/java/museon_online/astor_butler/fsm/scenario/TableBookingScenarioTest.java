package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.identity.IdentityService;
import museon_online.astor_butler.domain.media.AerisMediaCatalog;
import museon_online.astor_butler.domain.media.MediaAsset;
import museon_online.astor_butler.fsm.understanding.InputIntent;
import museon_online.astor_butler.fsm.understanding.SlotValue;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableBookingScenarioTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private TableBookingDraftStorage draftStorage;

    @Mock
    private TableReservationService tableReservationService;

    @Mock
    private AerisMediaCatalog mediaCatalog;

    @Mock
    private IdentityService identityService;

    /** The buttons under the summary, and the state the guest reads it in. */
    private static final String SEND = "✅ Отправить заявку";
    private static final String CHANGE = "✏️ Изменить";
    private static final String SUMMARY = "TABLE_BOOKING_CONFIRMATION";

    private TableBookingScenario scenario;
    private BookingTimeProvider timeProvider;
    private AtomicReference<TableBookingDraftStorage.Draft> kept;

    @BeforeEach
    void setUp() {
        timeProvider = new BookingTimeProvider(Clock.fixed(
                Instant.parse("2026-06-26T09:32:00Z"),
                BookingTimeProvider.VENUE_ZONE
        ));
        scenario = scenarioAt(timeProvider);
        lenient().when(mediaCatalog.floorPlan()).thenReturn(new MediaAsset(
                "AERIS_FLOOR_PLAN",
                "AERIS",
                "TABLE_BOOKING",
                "FLOOR_PLAN",
                "План зала AERIS",
                "astor-media",
                "content/aeris/floor-plan/AERIS_PLAN.pdf",
                "AERIS PLAN.pdf",
                "application/pdf",
                true
        ));
        lenient().when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));
    }

    private TableBookingScenario scenarioAt(BookingTimeProvider clock) {
        TableBookingDraftMerger draftMerger = new TableBookingDraftMerger(draftStorage, clock);
        ReflectionTestUtils.setField(draftMerger, "defaultVenueCode", "AERIS");
        TableBookingScenario created = new TableBookingScenario(
                fsmStorage,
                draftStorage,
                tableReservationService,
                mediaCatalog,
                draftMerger,
                new TableBookingStepRegistry(),
                new BookingPhraseService(),
                clock,
                new VenueOpeningHours(),
                identityService
        );
        ReflectionTestUtils.setField(created, "planPdfAssetCode", "AERIS_FLOOR_PLAN");
        ReflectionTestUtils.setField(created, "managerTelegramId", 876857557L);
        ReflectionTestUtils.setField(created, "hostessChatId", "-1004291419562");
        return created;
    }

    @Test
    void sendsHallPlanForCompleteInitialTableBookingRequest() {
        IncomingMessage incoming = telegram("Хочу забронировать столик завтра на 20:00 на двоих");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(outgoing.actions()).contains("SEND_HALL_PLAN", "ASK_TABLE_SELECTION");
        assertThat(outgoing.metadata()).containsEntry("documentObjectKey", "content/aeris/floor-plan/AERIS_PLAN.pdf");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION);
        verify(draftStorage).save(any(), any());
    }

    @Test
    void keepsExplicitTimeSlotWhenInitialRequestAlsoLooksLikeTableSelection() {
        IncomingMessage incoming = telegram("Хочу забронировать стол завтра в 20:00 на двоих, тихий стол в винной комнате");
        UnderstoodInput understood = new UnderstoodInput(
                incoming.text(),
                "хочу забронировать стол завтра в 20:00 на 2 гостей, тихий стол в винной комнате",
                InputIntent.TABLE_BOOKING,
                0.92,
                Map.of(
                        "date", new SlotValue("date", "завтра", 0.95),
                        "time", new SlotValue("time", "20:00", 0.98),
                        "partySize", new SlotValue("partySize", "2", 0.95),
                        "seatingPreference", new SlotValue("seatingPreference", "тихий стол в винной комнате", 0.9)
                ),
                List.of(InputIntent.TABLE_BOOKING),
                false,
                null
        );

        stored(null);

        OutgoingMessage summary = scenario.handle(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood);

        assertThat(summary.actions()).doesNotContain("ASK_TIME", "ASK_PARTY_SIZE", "ASK_TABLE_SELECTION");
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION");
        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().partySize()).isEqualTo(2);
        assertThat(commandCaptor.getValue().requestedStartAt().atZone(BookingTimeProvider.VENUE_ZONE).toLocalTime())
                .isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void asksPartySizeFirstForIncompleteInitialTableBookingRequest() {
        IncomingMessage incoming = telegram("Хочу забронировать столик");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
        assertThat(outgoing.actions()).containsExactly("ASK_PARTY_SIZE");
        assertThat(outgoing.text()).contains("на сколько гостей");
        assertThat(outgoing.metadata()).doesNotContainKey("documentObjectKey");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE);
    }

    @Test
    void supportsBookingButtonLabelFromMainMenu() {
        IncomingMessage incoming = telegram("Бронь стола");

        assertThat(scenario.supports(incoming, BotState.READY_FOR_DIALOG, incoming.text())).isTrue();

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
        assertThat(outgoing.actions()).containsExactly("ASK_PARTY_SIZE");
    }

    @Test
    void leavesAnAnswerInTheChangeStepToTheChangeScenario() {
        // ChangeCancelScenario puts the guest into this state and waits for the new time, day, table or party size.
        // The router asks this scenario first, so claiming the state here started a new booking instead.
        for (String answer : new String[]{"20:00", "4", "в пятницу", "у окна", "да"}) {
            IncomingMessage incoming = telegram(answer);

            assertThat(scenario.supports(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, incoming.text())).as(answer).isFalse();
        }
        assertThat(scenario.owns(BotState.TABLE_BOOKING_CHANGE_REQUESTED)).isFalse();
    }

    @Test
    void doesNotResendHallPlanDuringActiveSlotCollection() {
        IncomingMessage incoming = telegram("завтра");
        when(draftStorage.find(incoming.chatId())).thenReturn(Optional.of(new TableBookingDraftStorage.Draft(
                "AERIS",
                null,
                null,
                null,
                null,
                2,
                "18",
                null,
                null,
                "18 стол"
        )));

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_COLLECT_DATE, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(outgoing.actions()).containsExactly("ASK_TIME");
        assertThat(outgoing.metadata()).doesNotContainKey("documentObjectKey");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TABLE_BOOKING_COLLECT_TIME);
    }

    @Test
    void keepsExistingHallPlanWhenGuestRepeatsBookingIntentDuringTableSelection() {
        IncomingMessage incoming = telegram("Хочу забронировать столик завтра на 20:00 на двоих");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(outgoing.actions()).contains("USE_EXISTING_HALL_PLAN", "ASK_TABLE_SELECTION");
        assertThat(outgoing.actions()).doesNotContain("SEND_HALL_PLAN");
        assertThat(outgoing.metadata()).doesNotContainKey("documentObjectKey");
    }

    @Test
    void acceptsPartySizeReplyDuringTableSelectionWithoutRepeatingGuestQuestion() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                null,
                "7",
                "WINE_ROOM",
                "тихий стол в винной комнате",
                "хочу забронировать столик завтра в восемь вечера, тихий стол в винной комнате"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));

        OutgoingMessage summary = scenario.handle(
                telegram("На троих"),
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                "На троих"
        );

        assertThat(summary.actions()).doesNotContain("ASK_PARTY_SIZE", "SEND_HALL_PLAN");
        assertThat(summary.metadata()).doesNotContainKey("documentObjectKey");
        assertThat(storedDraft.get().partySize()).isEqualTo(3);
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        verify(tableReservationService).createReservation(any(TableReservationCommand.class));
    }

    @Test
    void doesNotTreatTableSelectionAsTimeWhileCollectingDate() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                null,
                null,
                null,
                null,
                2,
                null,
                null,
                null,
                "на двоих"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));

        OutgoingMessage stillNeedsDate = scenario.handle(
                telegram("18 стол"),
                BotState.TABLE_BOOKING_COLLECT_DATE,
                "18 стол"
        );

        assertThat(stillNeedsDate.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_DATE.name());
        assertThat(stillNeedsDate.actions()).contains("ASK_DATE");
        assertThat(storedDraft.get().requestedTime()).isNull();

        OutgoingMessage timePrompt = scenario.handle(
                telegram("На завтра"),
                BotState.TABLE_BOOKING_COLLECT_DATE,
                "На завтра"
        );

        assertThat(timePrompt.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(timePrompt.actions()).contains("ASK_TIME");
        assertThat(storedDraft.get().requestedDate()).isNotNull();
        assertThat(storedDraft.get().requestedTime()).isNull();
    }

    @Test
    void acceptsRussianPartySizeVariantsWhileCollectingPartySize() {
        for (String reply : java.util.List.of("На одного", "соло", "На троих", "3", "трое")) {
            AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                    "AERIS",
                    Instant.parse("2026-06-27T15:00:00Z"),
                    Instant.parse("2026-06-27T17:00:00Z"),
                    LocalDate.of(2026, 6, 27),
                    LocalTime.of(20, 0),
                    null,
                    "18",
                    null,
                    null,
                    "Хочу стол завтра в 20:00"
            ));
            doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                    .when(draftStorage).find(eq(1773317437L));
            doAnswer(invocation -> {
                storedDraft.set(invocation.getArgument(1));
                return null;
            }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
            OutgoingMessage summary = scenario.handle(
                    telegram(reply),
                    BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                    reply
            );

            assertThat(summary.actions()).as(reply).doesNotContain("ASK_PARTY_SIZE");
            int expectedPartySize = reply.equals("На одного") || reply.equals("соло") ? 1 : 3;
            assertThat(storedDraft.get().partySize()).as(reply).isEqualTo(expectedPartySize);
            assertThat(summary.text()).as(reply).contains("Гостей: " + expectedPartySize);
            OutgoingMessage plan = send(summary);
            assertThat(plan.nextState()).as(reply).isEqualTo(BotState.READY_FOR_DIALOG.name());
            assertThat(plan.actions()).as(reply).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        }
    }

    @Test
    void acceptsExplicitTableSelectionDuringTableSelection() {
        IncomingMessage incoming = telegram("17");
        stored(draft());

        OutgoingMessage summary = scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, incoming.text());

        assertThat(summary.text()).contains("Стол 17");
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
    }

    @Test
    void carriesSeatingPreferenceIntoReservationCommand() {
        IncomingMessage incoming = telegram("винная комната, тихий стол");
        Instant start = Instant.parse("2026-06-27T15:00:00Z");
        stored(new TableBookingDraftStorage.Draft(
                "AERIS",
                start,
                start.plusSeconds(7200),
                null,
                null,
                2,
                null,
                "MAIN_HALL",
                "тихий стол",
                "Хочу забронировать столик завтра на 20:00 на двоих"
        ));
        when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));

        send(scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, incoming.text()));

        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().preferredZone()).isEqualTo("WINE_ROOM");
        assertThat(commandCaptor.getValue().seatingPreference()).contains("тихий стол");
    }

    @Test
    void asksForDetailsWhenTableSelectedWithoutDraft() {
        IncomingMessage incoming = telegram("17");
        when(draftStorage.find(incoming.chatId())).thenReturn(Optional.empty());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
        assertThat(outgoing.actions()).contains("ASK_PARTY_SIZE");
        assertThat(outgoing.metadata()).doesNotContainKey("replyKeyboardRows");
    }

    @Test
    void interpretsCurrentFridayWithinCurrentWeekAndStillAsksForTime() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                null,
                null,
                null,
                null,
                2,
                "9",
                null,
                null,
                "9 стол"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));

        OutgoingMessage outgoing = scenario.handle(
                telegram("На пятницу"),
                BotState.TABLE_BOOKING_COLLECT_DATE,
                "На пятницу"
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(outgoing.actions()).contains("ASK_TIME");
        assertThat(outgoing.metadata()).containsKey("replyKeyboardRows");
        assertThat(storedDraft.get().requestedDate()).isEqualTo(LocalDate.of(2026, 6, 26));
        assertThat(storedDraft.get().requestedTime()).isNull();
        assertThat(storedDraft.get().requestedStartAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(tableReservationService);
    }

    @Test
    void collectsDateTimeAndPartySizeAcrossSeparateManualReplies() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>();
        LocalDate bookingDate = LocalDate.now(ZoneId.of("Asia/Yekaterinburg")).plusDays(7);
        String bookingDateText = bookingDate.format(DateTimeFormatter.ofPattern("dd.MM"));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));

        OutgoingMessage datePrompt = scenario.handle(
                telegram("Хочу забронировать столик"),
                BotState.READY_FOR_DIALOG,
                "Хочу забронировать столик"
        );
        assertThat(datePrompt.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
        assertThat(datePrompt.actions()).contains("ASK_PARTY_SIZE");

        OutgoingMessage planPrompt = scenario.handle(
                telegram("на двоих"),
                BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                "на двоих"
        );
        assertThat(planPrompt.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(planPrompt.actions()).contains("ASK_TABLE_SELECTION");
        assertThat(planPrompt.metadata()).containsEntry("documentObjectKey", "content/aeris/floor-plan/AERIS_PLAN.pdf");

        OutgoingMessage dateQuestion = scenario.handle(
                telegram("18 стол"),
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                "18 стол"
        );
        assertThat(dateQuestion.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_DATE.name());
        assertThat(dateQuestion.actions()).contains("ASK_DATE");

        OutgoingMessage timePrompt = scenario.handle(
                telegram(bookingDateText),
                BotState.TABLE_BOOKING_COLLECT_DATE,
                bookingDateText
        );
        assertThat(timePrompt.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(timePrompt.metadata()).containsKey("replyKeyboardRows");

        OutgoingMessage summary = scenario.handle(
                telegram("20:00"),
                BotState.TABLE_BOOKING_COLLECT_TIME,
                "20:00"
        );
        assertThat(summary.text()).contains("Стол 18\n" + bookingDateText + " в 20:00\nГостей: 2");
        assertThat(summary.actions()).doesNotContain("SEND_HALL_PLAN");
        assertThat(summary.metadata()).doesNotContainKey("documentObjectKey");
        verify(tableReservationService, never()).createReservation(any());

        OutgoingMessage created = send(summary);
        assertThat(created.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(created.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(storedDraft.get().requestedDate()).isEqualTo(bookingDate);
        assertThat(storedDraft.get().requestedTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(storedDraft.get().partySize()).isEqualTo(2);
        assertThat(storedDraft.get().tableCode()).isEqualTo("18");
        assertThat(storedDraft.get().requestedStartAt()).isNotNull();
    }

    @Test
    void autoSelectionCreatesReservationWithoutAskingAgain() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                3,
                null,
                null,
                null,
                "Хочу забронировать стол на троих завтра в 20:00"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));

        OutgoingMessage summary = scenario.handle(
                telegram("Подбери сам"),
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                "Подбери сам"
        );

        assertThat(summary.actions()).doesNotContain("ASK_TABLE_SELECTION");
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().tableCode()).isNull();
        assertThat(commandCaptor.getValue().preferredZone()).isNull();
        assertThat(commandCaptor.getValue().partySize()).isEqualTo(3);
    }

    /**
     * The contact was shared at the first touch, long before the booking; "Подбери сам" is plain text.
     * The reservation still carries that phone, so Saby takes it and the hostess card does not say "не указан".
     */
    @Test
    void theReservationCarriesThePhoneTheGuestSharedEarlier() {
        stored(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                3,
                null,
                null,
                null,
                "Хочу забронировать стол на троих завтра в 20:00"
        ));
        when(identityService.primaryPhone(1773317437L)).thenReturn(Optional.of("+79990000000"));

        OutgoingMessage outgoing = send(scenario.handle(telegram("Подбери сам"), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "Подбери сам"));

        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().guestPhone()).isEqualTo("+79990000000");
    }

    @Test
    void theGuestIsToldWhatWentToTheHostess() {
        stored(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                2,
                null,
                null,
                null,
                "Хочу забронировать стол на двоих завтра в 20:00"
        ));

        OutgoingMessage outgoing = send(scenario.handle(telegram("Подбери сам"), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "Подбери сам"));

        // The numbers are the ones of the request the service returned, not of the draft: that is what the hostess sees.
        assertThat(outgoing.text()).contains("Заявку #44 передал команде AERIS", "Стол 17\n06.06 в 20:00\nГостей: 2");
        assertThat(outgoing.text()).doesNotContain("Бронь подтверждена");
    }

    @Test
    void theGuestCanLeaveTheBookingAtAnyStep() {
        BotState[] steps = {
                BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                BotState.TABLE_BOOKING_COLLECT_DATE,
                BotState.TABLE_BOOKING_COLLECT_TIME
        };
        for (BotState step : steps) {
            for (String text : new String[]{"отмена", "Главное меню", "Отменить текущее действие", "стоп", "передумал"}) {
                OutgoingMessage outgoing = scenario.handle(telegram(text), step, text);

                assertThat(outgoing.nextState()).as(step + " / " + text).isEqualTo(BotState.READY_FOR_DIALOG.name());
                assertThat(outgoing.actions()).as(step + " / " + text).contains("TABLE_BOOKING_CANCELLED_BY_GUEST", "RETURN_MAIN_MENU");
                assertThat(outgoing.text()).as(step + " / " + text).contains("бронь не оформляю");
            }
        }
        verify(draftStorage, org.mockito.Mockito.times(20)).clear(1773317437L);
        verify(draftStorage, never()).save(any(), any());
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aWordThatOnlyLooksLikeLeavingIsStillAnAnswer() {
        // "Нет" is how a guest declines a seating wish, and "отменить бронь" belongs to the change and cancel dialogue.
        OutgoingMessage outgoing = scenario.handle(telegram("на двоих, без отмены"), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, "на двоих, без отмены");

        assertThat(outgoing.actions()).doesNotContain("TABLE_BOOKING_CANCELLED_BY_GUEST");
        verify(draftStorage, never()).clear(any());
    }

    @Test
    void theSameGuestDoesNotGetASecondTableForTheSameTime() {
        TableBookingDraftStorage.Draft complete = new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                2,
                null,
                null,
                null,
                "Хочу забронировать стол на двоих завтра в 20:00"
        );
        when(draftStorage.find(eq(1773317437L))).thenReturn(Optional.of(complete));
        // The guest already holds a table from 19:00 to 21:00 that evening; the rule that finds it lives in the reservation service.
        when(tableReservationService.findOverlappingReservation(1773317437L, "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"), Instant.parse("2026-06-27T17:00:00Z"))).thenReturn(Optional.of(new TableReservationOrder(
                51L, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4 · у окна", null, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM",
                Instant.parse("2026-06-27T14:00:00Z"), Instant.parse("2026-06-27T16:00:00Z"), 2,
                "Наталья Поединенко", null, null, 876857557L, null, "-1004291419562", null,
                Instant.parse("2026-06-26T09:00:00Z"), Instant.parse("2026-06-26T09:00:00Z"))));

        OutgoingMessage outgoing = scenario.handle(telegram("Подбери сам"), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "Подбери сам");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_ALREADY_EXISTS");
        assertThat(outgoing.text()).contains("уже есть заявка #51", "27.06", "19:00");
        verify(draftStorage).clear(1773317437L);
    }

    @Test
    void aGuestWithNothingInTheWayGetsTheTable() {
        TableBookingDraftStorage.Draft complete = new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                2,
                null,
                null,
                null,
                "Хочу забронировать стол на двоих завтра в 20:00"
        );
        stored(complete);
        // Another evening, a cancelled request and a table at another venue are told apart in TableReservationServiceTest.

        OutgoingMessage outgoing = send(scenario.handle(telegram("Подбери сам"), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "Подбери сам"));

        // The scenario asks about its own venue and exactly the hours it is about to hold: before the summary, so the guest
        // is not asked to confirm what cannot be sent, and again right before the request is created.
        verify(tableReservationService, org.mockito.Mockito.times(2)).findOverlappingReservation(1773317437L, "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"), Instant.parse("2026-06-27T17:00:00Z"));
        verify(tableReservationService).createReservation(any());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
    }

    @Test
    void aRequestThatAppearedWhileTheGuestReadTheSummaryIsStillInTheWay() {
        stored(new TableBookingDraftStorage.Draft(
                "AERIS", null, null, LocalDate.of(2026, 6, 27), null, 2, "5", null, null, true, "Забронировать стол"));
        OutgoingMessage summary = scenario.handle(telegram("20:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "20:00");
        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        // Meanwhile the same guest got a table for that evening another way: from the site, or with a second tap.
        when(tableReservationService.findOverlappingReservation(1773317437L, "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"), Instant.parse("2026-06-27T17:00:00Z"))).thenReturn(Optional.of(new TableReservationOrder(
                51L, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4 · у окна", null, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM",
                Instant.parse("2026-06-27T14:00:00Z"), Instant.parse("2026-06-27T16:00:00Z"), 2,
                "Наталья Поединенко", null, null, 876857557L, null, "-1004291419562", null,
                Instant.parse("2026-06-26T09:00:00Z"), Instant.parse("2026-06-26T09:00:00Z"))));

        OutgoingMessage outgoing = reply(summary, SEND);

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.actions()).contains("RESERVATION_ALREADY_EXISTS");
        assertThat(outgoing.text()).contains("уже есть заявка #51");
    }

    /** A table and two guests are chosen for Saturday 27.06; AERIS opens at 14:00 on Saturdays and closes at 04:00. */
    private AtomicReference<TableBookingDraftStorage.Draft> saturdayWithoutATime() {
        AtomicReference<TableBookingDraftStorage.Draft> stored = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS", null, null, LocalDate.of(2026, 6, 27), null, 2, "5", null, null, true, "Забронировать стол"));
        lenient().doAnswer(invocation -> Optional.ofNullable(stored.get())).when(draftStorage).find(eq(1773317437L));
        lenient().doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        return stored;
    }

    @SuppressWarnings("unchecked")
    private List<String> buttons(OutgoingMessage outgoing) {
        return ((List<List<String>>) outgoing.metadata().get("replyKeyboardRows")).stream().flatMap(List::stream).toList();
    }

    @Test
    void aTimeWhenTheVenueIsClosedIsExplainedAndAskedAgain() {
        AtomicReference<TableBookingDraftStorage.Draft> stored = saturdayWithoutATime();

        OutgoingMessage outgoing = scenario.handle(telegram("13:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "13:00");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(outgoing.actions()).contains("TIME_OUTSIDE_OPENING_HOURS", "ASK_TIME");
        assertThat(outgoing.text()).startsWith("В это время AERIS закрыт. В этот день ждем гостей с 14:00 до 04:00.");
        assertThat(outgoing.text()).doesNotContain("Не хочу гадать");
        assertThat(buttons(outgoing)).containsExactly("14:00", "15:00", "16:00", "17:00", "18:00", "19:00", "20:00", "21:00", "22:00", "23:00");
        assertThat(stored.get().requestedTime()).isNull();
        assertThat(stored.get().requestedDate()).isEqualTo(LocalDate.of(2026, 6, 27));
        assertThat(stored.get().partySize()).isEqualTo(2);
    }

    @Test
    void anHourAfterMidnightBelongsToTheEveningBefore() {
        saturdayWithoutATime();

        // One o'clock on Saturday night is still Friday's evening, which runs until 04:00.
        OutgoingMessage outgoing = send(scenario.handle(telegram("01:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "01:00"));

        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
        verify(tableReservationService).createReservation(any());
    }

    @Test
    void theMorningAfterClosingIsNotBooked() {
        saturdayWithoutATime();

        OutgoingMessage outgoing = scenario.handle(telegram("09:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "09:00");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.text()).startsWith("В это время AERIS закрыт.");
    }

    @Test
    void aClosedHourNamedAtOnceIsExplainedBeforeTheOtherQuestions() {
        lenient().when(draftStorage.find(any())).thenReturn(Optional.empty());

        OutgoingMessage outgoing = scenario.handle(telegram("Хочу забронировать столик завтра на 09:00 на двоих"), BotState.READY_FOR_DIALOG,
                "Хочу забронировать столик завтра на 09:00 на двоих");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.text()).startsWith("В это время AERIS закрыт. В этот день ждем гостей с 14:00 до 04:00.");
        assertThat(outgoing.actions()).contains("TIME_OUTSIDE_OPENING_HOURS");
    }

    @Test
    void understandsEveningTimeAndCompactPartySizeReplies() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                null,
                null,
                null,
                null,
                null,
                "18",
                null,
                null,
                "18 стол"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));

        scenario.handle(telegram("на 2х"), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, "на 2х");
        scenario.handle(telegram("на завтра"), BotState.TABLE_BOOKING_COLLECT_DATE, "на завтра");
        OutgoingMessage plan = send(scenario.handle(telegram("в 8 вечера"), BotState.TABLE_BOOKING_COLLECT_TIME, "в 8 вечера"));

        assertThat(plan.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(plan.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(storedDraft.get().requestedTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(storedDraft.get().partySize()).isEqualTo(2);
    }

    @Test
    void asksForTimeAfterDucklingWeekdayDateInsteadOfCreatingMidnightOrder() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                null,
                null,
                null,
                null,
                2,
                "9",
                null,
                null,
                "9 стол"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        UnderstoodInput understood = new UnderstoodInput(
                "На пятницу",
                "на пятницу",
                InputIntent.PROVIDE_DATE,
                0.9,
                Map.of("date", new SlotValue("date", "2026-07-03", 0.82)),
                List.of(InputIntent.PROVIDE_DATE),
                false,
                null
        );

        OutgoingMessage outgoing = scenario.handle(
                telegram("На пятницу"),
                BotState.TABLE_BOOKING_COLLECT_DATE,
                "На пятницу",
                understood
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(outgoing.actions()).contains("ASK_TIME");
        assertThat(storedDraft.get().requestedDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(storedDraft.get().requestedTime()).isNull();
        assertThat(storedDraft.get().requestedStartAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(tableReservationService);
    }

    @Test
    void usesUnderstandingSlotsInsteadOfRepeatingCurrentQuestion() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                null,
                "18",
                null,
                null,
                "18 стол | завтра | 20:00"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));

        UnderstoodInput understood = new UnderstoodInput(
                "На троих",
                "на троих",
                InputIntent.PROVIDE_PARTY_SIZE,
                0.93,
                Map.of("partySize", new SlotValue("partySize", "3", 0.98)),
                List.of(InputIntent.PROVIDE_PARTY_SIZE),
                false,
                null
        );

        OutgoingMessage summary = scenario.handle(
                telegram("На троих"),
                BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                "На троих",
                understood
        );

        assertThat(summary.actions()).doesNotContain("ASK_PARTY_SIZE");
        assertThat(storedDraft.get().partySize()).isEqualTo(3);
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
    }

    @Test
    void createsReservationAfterGuestDeclinesSeatingPreference() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                3,
                "18",
                null,
                null,
                false,
                "Хочу стол"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));

        // Here "нет" declines a seating wish; it is not the guest leaving the booking.
        OutgoingMessage summary = scenario.handle(
                telegram("нет"),
                BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                "нет"
        );

        assertThat(storedDraft.get().seatingPreference()).isNull();
        assertThat(storedDraft.get().seatingPreferenceResolved()).isTrue();
        assertThat(summary.text()).doesNotContain("Пожелание:");
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        verify(tableReservationService).createReservation(any(TableReservationCommand.class));
    }

    @Test
    void storesSeatingPreferenceBeforeCreatingReservation() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-27T15:00:00Z"),
                Instant.parse("2026-06-27T17:00:00Z"),
                LocalDate.of(2026, 6, 27),
                LocalTime.of(20, 0),
                2,
                "7",
                "WINE_ROOM",
                null,
                false,
                "Хочу стол"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));

        OutgoingMessage summary = scenario.handle(
                telegram("тихий стол не у прохода"),
                BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                "тихий стол не у прохода"
        );

        assertThat(storedDraft.get().seatingPreference()).contains("тихий стол");
        assertThat(storedDraft.get().seatingPreferenceResolved()).isTrue();
        assertThat(summary.text()).contains("Пожелание: тихий стол не у прохода");
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        verify(tableReservationService).createReservation(any(TableReservationCommand.class));
    }

    @Test
    void storesAnyFreeTextAsSeatingPreferenceAtPreferenceStep() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-07-03T15:00:00Z"),
                Instant.parse("2026-07-03T17:00:00Z"),
                LocalDate.of(2026, 7, 3),
                LocalTime.of(20, 0),
                3,
                "9",
                null,
                null,
                false,
                "Забронировать стол | 9 стол | На пятницу | 20:00 | На троих"
        ));
        doAnswer(invocation -> Optional.ofNullable(storedDraft.get()))
                .when(draftStorage).find(eq(1773317437L));
        doAnswer(invocation -> {
            storedDraft.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(77L));

        OutgoingMessage summary = scenario.handle(
                telegram("Возможно будет четыре"),
                BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                "Возможно будет четыре"
        );

        assertThat(storedDraft.get().seatingPreference()).isEqualTo("возможно будет четыре");
        assertThat(storedDraft.get().seatingPreferenceResolved()).isTrue();
        OutgoingMessage outgoing = send(summary);
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().seatingPreference()).isEqualTo("возможно будет четыре");
    }

    /** The clock of these tests is Friday 26.06.2026, 14:32 in Yekaterinburg; on Fridays AERIS is open from 12:00 to 04:00. */
    private AtomicReference<TableBookingDraftStorage.Draft> stored(TableBookingDraftStorage.Draft draft) {
        AtomicReference<TableBookingDraftStorage.Draft> stored = new AtomicReference<>(draft);
        lenient().doAnswer(invocation -> Optional.ofNullable(stored.get())).when(draftStorage).find(eq(1773317437L));
        lenient().doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(draftStorage).save(eq(1773317437L), any(TableBookingDraftStorage.Draft.class));
        lenient().doAnswer(invocation -> {
            stored.set(null);
            return null;
        }).when(draftStorage).clear(eq(1773317437L));
        return stored;
    }

    /** What the guest says next, in the state the previous reply left the dialogue in. */
    private OutgoingMessage reply(OutgoingMessage previous, String text) {
        return reply(scenario, previous, text);
    }

    private OutgoingMessage reply(TableBookingScenario booking, OutgoingMessage previous, String text) {
        return booking.handle(telegram(text), BotState.valueOf(previous.nextState()), text);
    }

    /** The last answer of the booking gave a summary and created nothing; the guest reads it and sends the request. */
    private OutgoingMessage send(OutgoingMessage summary) {
        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        assertThat(summary.actions()).containsExactly("ASK_BOOKING_CONFIRMATION");
        return reply(summary, SEND);
    }

    /** Table 5, Saturday 27.06, two guests; "20:00" is the last answer, so what comes back is the summary. */
    private OutgoingMessage atTheSummary() {
        kept = stored(new TableBookingDraftStorage.Draft(
                "AERIS", null, null, LocalDate.of(2026, 6, 27), null, 2, "5", null, null, true, "Забронировать стол"));
        return scenario.handle(telegram("20:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "20:00");
    }

    @Test
    void theGuestSeesASummaryBeforeAnythingIsSent() {
        OutgoingMessage summary = atTheSummary();

        verify(tableReservationService, never()).createReservation(any());
        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        assertThat(summary.actions()).containsExactly("ASK_BOOKING_CONFIRMATION");
        assertThat(summary.text()).isEqualTo("""
                Проверьте, пожалуйста, бронь:

                Стол 5
                27.06 в 20:00
                Гостей: 2

                Отправляю заявку команде AERIS? Если что-то не так, напишите коротко: «в 20:00», «на троих», «стол 5». \
                Пожелания тоже можно написать одной строкой, я передам.""");
        assertThat(buttons(summary)).containsExactly(SEND, CHANGE);
        verify(fsmStorage).setState(1773317437L, BotState.valueOf(SUMMARY));
        assertThat(kept.get().requestedTime()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void aTableLeftToTheTeamIsNamedAsSuchInTheSummary() {
        stored(new TableBookingDraftStorage.Draft(
                "AERIS", null, null, LocalDate.of(2026, 6, 27), LocalTime.of(20, 0), 2, null, null, null, false, "Хочу стол на двоих завтра в 20:00"));

        OutgoingMessage summary = scenario.handle(telegram("Подбери сам"), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "Подбери сам");

        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        // "Подбери сам" is the choice of a table, not a wish to pass on.
        assertThat(summary.text()).contains("Стол: подберем свободный\n27.06 в 20:00\nГостей: 2").doesNotContain("Пожелание:");
    }

    @Test
    void theRequestIsSentOnlyOnTheGuestsWord() {
        String[] yes = {SEND, "да", "Да, все верно", "ок", "хорошо", "подтверждаю", "отправляйте, пожалуйста"};
        for (String word : yes) {
            OutgoingMessage sent = reply(atTheSummary(), word);

            assertThat(sent.actions()).as(word).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
            assertThat(sent.nextState()).as(word).isEqualTo(BotState.READY_FOR_DIALOG.name());
            assertThat(kept.get()).as(word).isNull();
        }
        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService, org.mockito.Mockito.times(yes.length)).createReservation(command.capture());
        // What goes to the hostess is what the summary showed. The button itself is not passed on as the guest's words.
        assertThat(command.getAllValues()).allSatisfy(sent -> {
            assertThat(sent.tableCode()).isEqualTo("5");
            assertThat(sent.requestedStartAt()).isEqualTo(Instant.parse("2026-06-27T15:00:00Z"));
            assertThat(sent.partySize()).isEqualTo(2);
            assertThat(sent.guestComment()).isEqualTo("Забронировать стол | 20:00");
        });
    }

    @Test
    void aShortReplyCorrectsTheSummaryAndNothingIsSentYet() {
        OutgoingMessage later = reply(atTheSummary(), "лучше в 21:00");
        assertThat(later.nextState()).isEqualTo(SUMMARY);
        assertThat(later.actions()).containsExactly("ASK_BOOKING_CONFIRMATION");
        assertThat(later.text()).startsWith("Поправил.").contains("Стол 5\n27.06 в 21:00\nГостей: 2");
        assertThat(buttons(later)).containsExactly(SEND, CHANGE);

        OutgoingMessage three = reply(later, "на троих");
        assertThat(three.text()).startsWith("Поправил.").contains("Стол 5\n27.06 в 21:00\nГостей: 3");

        OutgoingMessage table = reply(three, "стол 7");
        assertThat(table.text()).startsWith("Поправил.").contains("Стол 7\n27.06 в 21:00\nГостей: 3");

        OutgoingMessage day = reply(table, "в воскресенье");
        assertThat(day.nextState()).isEqualTo(SUMMARY);
        assertThat(day.text()).startsWith("Поправил.").contains("Стол 7\n28.06 в 21:00\nГостей: 3");
        verify(tableReservationService, never()).createReservation(any());

        reply(day, SEND);

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().tableCode()).isEqualTo("7");
        assertThat(command.getValue().partySize()).isEqualTo(3);
        assertThat(command.getValue().requestedStartAt()).isEqualTo(Instant.parse("2026-06-28T16:00:00Z"));
        assertThat(command.getValue().requestedEndAt()).isEqualTo(Instant.parse("2026-06-28T18:00:00Z"));
    }

    @Test
    void aReplyThatRepeatsTheSummaryChangesNothing() {
        OutgoingMessage same = reply(atTheSummary(), "в 20:00");

        // It names the time, so it is not kept as a wish; the time is already the one in the summary.
        assertThat(same.nextState()).isEqualTo(SUMMARY);
        assertThat(same.text()).startsWith("Проверьте, пожалуйста, бронь:").contains("27.06 в 20:00").doesNotContain("Пожелание:", "Поправил");
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aCorrectionToAnHourWhenTheVenueIsClosedIsExplainedAndNotSent() {
        OutgoingMessage closed = reply(atTheSummary(), "в 9 утра");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(closed.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(closed.actions()).contains("TIME_OUTSIDE_OPENING_HOURS", "ASK_TIME");
        assertThat(closed.text()).startsWith("В это время AERIS закрыт. В этот день ждем гостей с 14:00 до 04:00.");

        OutgoingMessage again = reply(closed, "19:00");

        assertThat(again.nextState()).isEqualTo(SUMMARY);
        assertThat(again.text()).contains("Стол 5\n27.06 в 19:00\nГостей: 2");
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aLongerReplyIsAWishForTheTeamAndIsNotSearchedForNumbers() {
        // "Один гость" must not turn the party of two into one, and "стул" is not a table.
        OutgoingMessage wish = reply(atTheSummary(), "Один гость не ест лук, и нужен детский стул");

        assertThat(wish.nextState()).isEqualTo(SUMMARY);
        assertThat(wish.text()).startsWith("Пожелание записал.")
                .contains("Стол 5\n27.06 в 20:00\nГостей: 2\nПожелание: Один гость не ест лук, и нужен детский стул");
        assertThat(buttons(wish)).containsExactly(SEND, CHANGE);
        verify(tableReservationService, never()).createReservation(any());

        reply(wish, SEND);

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().partySize()).isEqualTo(2);
        assertThat(command.getValue().tableCode()).isEqualTo("5");
        // The hostess reads the wish with the guest's own words, marked as a wish.
        assertThat(command.getValue().guestComment())
                .isEqualTo("Забронировать стол | 20:00 | Пожелание: Один гость не ест лук, и нужен детский стул");
    }

    @Test
    void aShortReplyThatNamesNothingOfTheVisitIsAWishToo() {
        OutgoingMessage chair = reply(atTheSummary(), "детский стул");
        assertThat(chair.text()).startsWith("Пожелание записал.").contains("Гостей: 2\nПожелание: детский стул");

        OutgoingMessage both = reply(chair, "у нас годовщина");
        assertThat(both.nextState()).isEqualTo(SUMMARY);
        assertThat(both.text()).contains("Стол 5\n27.06 в 20:00\nГостей: 2\nПожелание: детский стул; у нас годовщина");
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aSmallNumberAtTheSummaryIsNotTakenForAnHour() {
        // The summary itself suggests «на троих». Typed with a digit it is the party too, not four in the morning.
        for (String party : new String[]{"на 4", "нас 4"}) {
            OutgoingMessage four = reply(atTheSummary(), party);
            assertThat(four.nextState()).as(party).isEqualTo(SUMMARY);
            assertThat(four.text()).as(party).startsWith("Поправил.").contains("Стол 5\n27.06 в 20:00\nГостей: 4");
        }
        // A wish with a number in it stays a wish and does not send the guest back to the question about the time.
        for (String wish : new String[]{"2 детских стула", "торт на 5 свечей"}) {
            OutgoingMessage kept = reply(atTheSummary(), wish);
            assertThat(kept.nextState()).as(wish).isEqualTo(SUMMARY);
            assertThat(kept.text()).as(wish).startsWith("Пожелание записал.").contains("Стол 5\n27.06 в 20:00\nГостей: 2\nПожелание: " + wish);
        }
        // An hour of the evening is heard as before, said as an hour or not.
        for (String hour : new String[]{"лучше 21", "на 21", "21", "к 9 вечера", "в 21"}) {
            OutgoingMessage later = reply(atTheSummary(), hour);
            assertThat(later.nextState()).as(hour).isEqualTo(SUMMARY);
            assertThat(later.text()).as(hour).startsWith("Поправил.").contains("Стол 5\n27.06 в 21:00\nГостей: 2");
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aThankYouIsNotKeptAsAWish() {
        OutgoingMessage again = reply(atTheSummary(), "Спасибо!");

        assertThat(again.nextState()).isEqualTo(SUMMARY);
        assertThat(again.text()).startsWith("Проверьте, пожалуйста, бронь:").doesNotContain("Пожелание:");
        assertThat(kept.get().originalText()).isEqualTo("Забронировать стол | 20:00");
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void changeStartsTheBookingOverAndNothingIsSent() {
        for (String word : new String[]{CHANGE, "изменить", "давайте заново"}) {
            OutgoingMessage again = reply(atTheSummary(), word);

            assertThat(again.nextState()).as(word).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
            assertThat(again.actions()).as(word).containsExactly("ASK_PARTY_SIZE");
            assertThat(again.text()).as(word).startsWith("Хорошо, соберем заново.").contains("на сколько гостей");
            // The buttons of the summary go away with it.
            assertThat(again.removeKeyboard()).as(word).isTrue();
            assertThat(kept.get()).as(word).isNull();
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void noAtTheSummaryLeavesTheBookingWithoutARequest() {
        for (String word : new String[]{"нет", "Нет, спасибо", "не надо", "отмена", "передумал", "не отправляйте", "Отменить бронь"}) {
            OutgoingMessage left = reply(atTheSummary(), word);

            assertThat(left.nextState()).as(word).isEqualTo(BotState.READY_FOR_DIALOG.name());
            assertThat(left.actions()).as(word).contains("TABLE_BOOKING_CANCELLED_BY_GUEST", "RETURN_MAIN_MENU");
            assertThat(left.text()).as(word).contains("бронь не оформляю");
            assertThat(kept.get()).as(word).isNull();
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aReplyThatIsNotAPlainYesSendsNothing() {
        // "Да, но..." is not a yes, "не знаю" is not a no: the summary stays and waits for a clear word.
        for (String word : new String[]{"да, но лучше попозже, часов в девять", "да нет", "не знаю", "спасибо", "?"}) {
            OutgoingMessage again = reply(atTheSummary(), word);

            assertThat(again.nextState()).as(word).isEqualTo(SUMMARY);
            assertThat(again.actions()).as(word).containsExactly("ASK_BOOKING_CONFIRMATION");
            assertThat(buttons(again)).as(word).containsExactly(SEND, CHANGE);
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aPartyTooLargeNamedAtTheSummaryIsToldTheLimit() {
        // "На 21" is not here: at the summary that is nine in the evening.
        for (String party : new String[]{"на 25 человек", "нас 21", "на 30"}) {
            OutgoingMessage again = reply(atTheSummary(), party);

            assertThat(again.nextState()).as(party).isEqualTo(SUMMARY);
            assertThat(again.text()).as(party).startsWith("В одну бронь стола могу записать до 20 гостей.")
                    .contains("27.06 в 20:00\nГостей: 2").doesNotContain("Пожелание:");
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aSummaryWhoseDraftIsGoneSendsNothing() {
        OutgoingMessage summary = atTheSummary();
        // The draft is kept for a day. The guest comes back to the old summary later and presses the button under it.
        kept.set(null);

        OutgoingMessage late = reply(summary, SEND);

        verify(tableReservationService, never()).createReservation(any());
        assertThat(late.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
        assertThat(late.actions()).containsExactly("ASK_PARTY_SIZE");
        assertThat(late.text()).startsWith("Данные этой брони не сохранились, заявку я не отправлял.").contains("на сколько гостей");
    }

    @Test
    void aSummaryReadAfterItsTimeHasPassedIsNotSent() {
        OutgoingMessage summary = atTheSummary();
        // The same Saturday, 21:30: the table was asked for 20:00.
        TableBookingScenario laterThatEvening = scenarioAt(new BookingTimeProvider(Clock.fixed(Instant.parse("2026-06-27T16:30:00Z"), BookingTimeProvider.VENUE_ZONE)));

        OutgoingMessage late = reply(laterThatEvening, summary, SEND);

        verify(tableReservationService, never()).createReservation(any());
        assertThat(late.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(late.actions()).contains("TIME_ALREADY_PASSED", "ASK_TIME");
        assertThat(late.text()).startsWith("Это время уже прошло.");
        assertThat(buttons(late)).startsWith("22:00", "23:00");
    }

    @Test
    void aSummaryLeftUntilItsDayIsOverStartsTheBookingAgain() {
        // Sunday afternoon: no hour of Saturday is left to offer, so the day is not kept either.
        TableBookingScenario nextDay = scenarioAt(new BookingTimeProvider(Clock.fixed(Instant.parse("2026-06-28T09:00:00Z"), BookingTimeProvider.VENUE_ZONE)));

        for (String word : new String[]{SEND, "лучше в 21:00"}) {
            OutgoingMessage late = reply(nextDay, atTheSummary(), word);

            assertThat(late.nextState()).as(word).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
            assertThat(late.text()).as(word).startsWith("День этой брони уже прошел, заявку я не отправлял.").contains("на сколько гостей");
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aLateHourAlreadyGoneIsNotMovedToAnotherNightBehindTheSummary() {
        todayWithoutATime();
        // "Сегодня в 00:30", said on Friday afternoon, is the coming night: the summary shows 27.06.
        OutgoingMessage summary = scenario.handle(telegram("00:30"), BotState.TABLE_BOOKING_COLLECT_TIME, "00:30");
        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        assertThat(summary.text()).contains("27.06 в 00:30");
        // Saturday 15:00: that night is over. The guest never saw 28.06, so the request is not sent for it.
        TableBookingScenario saturdayAfternoon = scenarioAt(new BookingTimeProvider(Clock.fixed(Instant.parse("2026-06-27T10:00:00Z"), BookingTimeProvider.VENUE_ZONE)));

        OutgoingMessage late = reply(saturdayAfternoon, summary, SEND);

        verify(tableReservationService, never()).createReservation(any());
        assertThat(late.text()).startsWith("Это время уже прошло.");
    }

    @Test
    void aTableThatCannotBeHeldIsChosenAgainAndSummarisedAgain() {
        OutgoingMessage summary = atTheSummary();
        when(tableReservationService.createReservation(any(TableReservationCommand.class)))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Table is not available"));

        OutgoingMessage refused = reply(summary, SEND);

        assertThat(refused.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(refused.actions()).contains("TABLE_SELECTION_REJECTED", "ASK_TABLE_SELECTION");
        // The buttons of the summary would send the same table again.
        assertThat(refused.removeKeyboard()).isTrue();

        OutgoingMessage again = reply(refused, "7");

        assertThat(again.nextState()).isEqualTo(SUMMARY);
        assertThat(again.text()).contains("Стол 7\n27.06 в 20:00\nГостей: 2");
        verify(tableReservationService).createReservation(any());
    }

    @Test
    void theSummaryStepBelongsToTheTableBooking() {
        BotState summary = BotState.valueOf(SUMMARY);

        assertThat(scenario.owns(summary)).isTrue();
        for (String answer : new String[]{SEND, CHANGE, "нет", "лучше в 21:00", "у нас годовщина"}) {
            assertThat(scenario.supports(telegram(answer), summary, answer)).as(answer).isTrue();
        }
    }

    private AtomicReference<TableBookingDraftStorage.Draft> todayWithoutATime() {
        return stored(new TableBookingDraftStorage.Draft(
                "AERIS", null, null, LocalDate.of(2026, 6, 26), null, 2, "5", null, null, true, "Забронировать стол"));
    }

    @Test
    void aTimeAlreadyGoneTodayIsNotBooked() {
        AtomicReference<TableBookingDraftStorage.Draft> stored = todayWithoutATime();

        // It is 14:32, and the guest asks for one o'clock today.
        OutgoingMessage outgoing = scenario.handle(telegram("13:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "13:00");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_TIME.name());
        assertThat(outgoing.actions()).contains("TIME_ALREADY_PASSED", "ASK_TIME");
        assertThat(outgoing.text()).startsWith("Это время уже прошло.").doesNotContain("Не хочу гадать", "закрыт");
        assertThat(buttons(outgoing)).startsWith("15:00", "16:00").doesNotContain("12:00", "13:00", "14:00");
        assertThat(stored.get().requestedTime()).isNull();
        assertThat(stored.get().requestedDate()).isEqualTo(LocalDate.of(2026, 6, 26));
    }

    @Test
    void aLateHourSaidForTodayIsTheComingNight() {
        AtomicReference<TableBookingDraftStorage.Draft> stored = todayWithoutATime();

        // Half past midnight "today", said in the afternoon, is the night after this evening: 27.06 on the calendar.
        // The guest said "сегодня", so the summary is where they see the date the request really goes for.
        OutgoingMessage summary = scenario.handle(telegram("00:30"), BotState.TABLE_BOOKING_COLLECT_TIME, "00:30");
        assertThat(summary.text()).contains("27.06 в 00:30");
        OutgoingMessage outgoing = send(summary);

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().requestedStartAt()).isEqualTo(Instant.parse("2026-06-26T19:30:00Z"));
        assertThat(command.getValue().requestedEndAt()).isEqualTo(Instant.parse("2026-06-26T21:30:00Z"));
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
        verify(draftStorage).save(eq(1773317437L), org.mockito.ArgumentMatchers.argThat(
                draft -> LocalDate.of(2026, 6, 27).equals(draft.requestedDate()) && LocalTime.of(0, 30).equals(draft.requestedTime())));
    }

    @Test
    void aLateHourJustGoneIsNotMovedToTheNextNight() {
        // Saturday 27.06, 00:30: Friday's evening is still going on until 04:00. "Сегодня в 00:10" is twenty minutes ago.
        TableBookingScenario afterMidnight = scenarioAt(new BookingTimeProvider(Clock.fixed(Instant.parse("2026-06-26T19:30:00Z"), BookingTimeProvider.VENUE_ZONE)));
        stored(new TableBookingDraftStorage.Draft("AERIS", null, null, LocalDate.of(2026, 6, 27), null, 2, "5", null, null, true, "Забронировать стол"));

        OutgoingMessage outgoing = afterMidnight.handle(telegram("00:10"), BotState.TABLE_BOOKING_COLLECT_TIME, "00:10");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.text()).startsWith("Это время уже прошло.");
        assertThat(outgoing.actions()).contains("TIME_ALREADY_PASSED");
    }

    @Test
    void anHourOfTheSameNightStillAheadIsBookedAsItIs() {
        // The same night at 00:30: one o'clock is half an hour ahead and stays on 27.06.
        TableBookingScenario afterMidnight = scenarioAt(new BookingTimeProvider(Clock.fixed(Instant.parse("2026-06-26T19:30:00Z"), BookingTimeProvider.VENUE_ZONE)));
        stored(new TableBookingDraftStorage.Draft("AERIS", null, null, LocalDate.of(2026, 6, 27), null, 2, "5", null, null, true, "Забронировать стол"));

        OutgoingMessage summary = afterMidnight.handle(telegram("01:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "01:00");
        assertThat(summary.text()).contains("27.06 в 01:00");
        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        reply(afterMidnight, summary, SEND);

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().requestedStartAt()).isEqualTo(Instant.parse("2026-06-26T20:00:00Z"));
    }

    @Test
    void anEveningHourStillAheadStaysOnItsDay() {
        todayWithoutATime();

        send(scenario.handle(telegram("23:00"), BotState.TABLE_BOOKING_COLLECT_TIME, "23:00"));

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().requestedStartAt()).isEqualTo(Instant.parse("2026-06-26T18:00:00Z"));
    }

    @Test
    void aTimeAlreadyGoneNamedAtOnceIsNotBookedEither() {
        lenient().when(draftStorage.find(any())).thenReturn(Optional.empty());

        OutgoingMessage outgoing = scenario.handle(telegram("Хочу забронировать столик сегодня на 13:00 на двоих"), BotState.READY_FOR_DIALOG,
                "Хочу забронировать столик сегодня на 13:00 на двоих");

        verify(tableReservationService, never()).createReservation(any());
        assertThat(outgoing.text()).startsWith("Это время уже прошло.");
        assertThat(outgoing.actions()).contains("TIME_ALREADY_PASSED");
    }

    @Test
    void aPartyTooLargeForOneTableIsToldTheLimit() {
        lenient().when(draftStorage.find(any())).thenReturn(Optional.empty());
        for (String reply : new String[]{"на 25 человек", "100", "нас 30", "45 гостей"}) {
            OutgoingMessage outgoing = scenario.handle(telegram(reply), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, reply.toLowerCase());

            assertThat(outgoing.nextState()).as(reply).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
            assertThat(outgoing.actions()).as(reply).containsExactly("ASK_PARTY_SIZE");
            assertThat(outgoing.text()).as(reply).isEqualTo(
                    "В одну бронь стола могу записать до 20 гостей. Для большей компании напишите «менеджер», команда AERIS подберет вариант.");
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aReplyThatIsNotACountOfGuestsIsNotAskedInTheSameWordsAgain() {
        lenient().when(draftStorage.find(any())).thenReturn(Optional.empty());
        // "21.06" at this step is a date, not twenty-one guests.
        for (String reply : new String[]{"нас много", "0", "21.06"}) {
            OutgoingMessage outgoing = scenario.handle(telegram(reply), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, reply);

            assertThat(outgoing.nextState()).as(reply).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
            assertThat(outgoing.text()).as(reply).isEqualTo("Не понял, сколько будет гостей. Напишите число, например 4.");
        }
    }

    @Test
    void aDayAlreadyGoneIsNamedAsSuch() {
        for (String reply : new String[]{"вчера", "позавчера"}) {
            stored(new TableBookingDraftStorage.Draft("AERIS", null, null, null, null, 2, "5", null, null, true, "Забронировать стол"));

            OutgoingMessage outgoing = scenario.handle(telegram(reply), BotState.TABLE_BOOKING_COLLECT_DATE, reply);

            assertThat(outgoing.nextState()).as(reply).isEqualTo(BotState.TABLE_BOOKING_COLLECT_DATE.name());
            assertThat(outgoing.text()).as(reply).isEqualTo("Этот день уже прошел. На какой день держим стол?");
            assertThat(buttons(outgoing)).as(reply).isNotEmpty();
        }
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aReplyThatIsNotADayIsAskedAgainWithExamples() {
        stored(new TableBookingDraftStorage.Draft("AERIS", null, null, null, null, 2, "5", null, null, true, "Забронировать стол"));

        OutgoingMessage outgoing = scenario.handle(telegram("когда-нибудь"), BotState.TABLE_BOOKING_COLLECT_DATE, "когда-нибудь");

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_DATE.name());
        assertThat(outgoing.text()).isEqualTo("Не смог понять день. Выберите его кнопкой или напишите, например, «завтра», «в пятницу», «30.06».");
        assertThat(outgoing.text()).doesNotContain("Принял");
    }

    private IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(
                1773317437L,
                1773317437L,
                356,
                284069928,
                text,
                null,
                "Наталья",
                "Поединенко",
                "Poedinenko",
                "ru",
                false,
                "284069928"
        );
    }

    private TableBookingDraftStorage.Draft draft() {
        Instant start = Instant.parse("2026-06-27T15:00:00Z");
        return new TableBookingDraftStorage.Draft("AERIS", start, start.plusSeconds(7200), 2, "Хочу забронировать столик завтра на 20:00 на двоих");
    }

    private TableReservationOrder order(Long id) {
        return new TableReservationOrder(
                id,
                1773317437L,
                1773317437L,
                null,
                17L,
                "17",
                "Table 17",
                null,
                null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                "TELEGRAM",
                Instant.parse("2026-06-06T15:00:00Z"),
                Instant.parse("2026-06-06T17:00:00Z"),
                2,
                "Наталья Поединенко",
                null,
                "Хочу забронировать столик завтра на 20:00 на двоих",
                876857557L,
                null,
                "-1004291419562",
                null,
                Instant.parse("2026-06-05T15:00:00Z"),
                Instant.parse("2026-06-05T15:00:00Z")
        );
    }
}
