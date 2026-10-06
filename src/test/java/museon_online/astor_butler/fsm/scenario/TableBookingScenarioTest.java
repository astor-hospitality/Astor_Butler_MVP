package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
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

    private TableBookingScenario scenario;
    private BookingTimeProvider timeProvider;

    @BeforeEach
    void setUp() {
        timeProvider = new BookingTimeProvider(Clock.fixed(
                Instant.parse("2026-06-26T09:32:00Z"),
                BookingTimeProvider.VENUE_ZONE
        ));
        TableBookingDraftMerger draftMerger = new TableBookingDraftMerger(draftStorage, timeProvider);
        ReflectionTestUtils.setField(draftMerger, "defaultVenueCode", "AERIS");
        scenario = new TableBookingScenario(
                fsmStorage,
                draftStorage,
                tableReservationService,
                mediaCatalog,
                draftMerger,
                new TableBookingStepRegistry(),
                new BookingPhraseService(),
                timeProvider
        );
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
        ReflectionTestUtils.setField(scenario, "planPdfAssetCode", "AERIS_FLOOR_PLAN");
        ReflectionTestUtils.setField(scenario, "managerTelegramId", 876857557L);
        ReflectionTestUtils.setField(scenario, "hostessChatId", "-1004291419562");
        lenient().when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));
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

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION");
        assertThat(outgoing.actions()).doesNotContain("ASK_TIME", "ASK_PARTY_SIZE", "ASK_TABLE_SELECTION");
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
                Instant.parse("2026-06-06T15:00:00Z"),
                Instant.parse("2026-06-06T17:00:00Z"),
                LocalDate.of(2026, 6, 6),
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

        OutgoingMessage outgoing = scenario.handle(
                telegram("На троих"),
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                "На троих"
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(outgoing.actions()).doesNotContain("ASK_PARTY_SIZE", "SEND_HALL_PLAN");
        assertThat(outgoing.metadata()).doesNotContainKey("documentObjectKey");
        assertThat(storedDraft.get().partySize()).isEqualTo(3);
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
                    Instant.parse("2026-06-06T15:00:00Z"),
                    Instant.parse("2026-06-06T17:00:00Z"),
                    LocalDate.of(2026, 6, 6),
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
            OutgoingMessage plan = scenario.handle(
                    telegram(reply),
                    BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                    reply
            );

            assertThat(plan.nextState()).as(reply).isEqualTo(BotState.READY_FOR_DIALOG.name());
            assertThat(plan.actions()).as(reply).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
            assertThat(plan.actions()).as(reply).doesNotContain("ASK_PARTY_SIZE");
            int expectedPartySize = reply.equals("На одного") || reply.equals("соло") ? 1 : 3;
            assertThat(storedDraft.get().partySize()).as(reply).isEqualTo(expectedPartySize);
        }
    }

    @Test
    void acceptsExplicitTableSelectionDuringTableSelection() {
        IncomingMessage incoming = telegram("17");
        when(draftStorage.find(incoming.chatId())).thenReturn(Optional.of(draft()));

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
    }

    @Test
    void carriesSeatingPreferenceIntoReservationCommand() {
        IncomingMessage incoming = telegram("винная комната, тихий стол");
        Instant start = Instant.parse("2026-06-06T15:00:00Z");
        when(draftStorage.find(incoming.chatId())).thenReturn(Optional.of(new TableBookingDraftStorage.Draft(
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
        )));
        when(tableReservationService.createReservation(any(TableReservationCommand.class))).thenReturn(order(44L));

        scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, incoming.text());

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

        OutgoingMessage created = scenario.handle(
                telegram("20:00"),
                BotState.TABLE_BOOKING_COLLECT_TIME,
                "20:00"
        );
        assertThat(created.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(created.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(created.actions()).doesNotContain("SEND_HALL_PLAN");
        assertThat(created.metadata()).doesNotContainKey("documentObjectKey");
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

        OutgoingMessage outgoing = scenario.handle(
                telegram("Подбери сам"),
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                "Подбери сам"
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().tableCode()).isNull();
        assertThat(commandCaptor.getValue().preferredZone()).isNull();
        assertThat(commandCaptor.getValue().partySize()).isEqualTo(3);
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
        // The guest already holds a table from 19:00 to 21:00 that evening.
        when(tableReservationService.listActiveReservationsByChatId(1773317437L)).thenReturn(List.of(new TableReservationOrder(
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
    void anotherEveningIsNotADuplicate() {
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
        when(tableReservationService.listActiveReservationsByChatId(1773317437L)).thenReturn(List.of(new TableReservationOrder(
                51L, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4 · у окна", null, null,
                TableReservationStatus.CONFIRMED, "TELEGRAM",
                Instant.parse("2026-06-28T15:00:00Z"), Instant.parse("2026-06-28T17:00:00Z"), 2,
                "Наталья Поединенко", null, null, 876857557L, null, "-1004291419562", null,
                Instant.parse("2026-06-26T09:00:00Z"), Instant.parse("2026-06-26T09:00:00Z"))));

        OutgoingMessage outgoing = scenario.handle(telegram("Подбери сам"), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "Подбери сам");

        verify(tableReservationService).createReservation(any());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED");
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
        OutgoingMessage plan = scenario.handle(telegram("в 8 вечера"), BotState.TABLE_BOOKING_COLLECT_TIME, "в 8 вечера");

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
                Instant.parse("2026-06-06T15:00:00Z"),
                Instant.parse("2026-06-06T17:00:00Z"),
                LocalDate.of(2026, 6, 6),
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

        OutgoingMessage outgoing = scenario.handle(
                telegram("На троих"),
                BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                "На троих",
                understood
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(outgoing.actions()).doesNotContain("ASK_PARTY_SIZE");
        assertThat(storedDraft.get().partySize()).isEqualTo(3);
    }

    @Test
    void createsReservationAfterGuestDeclinesSeatingPreference() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-06T15:00:00Z"),
                Instant.parse("2026-06-06T17:00:00Z"),
                LocalDate.of(2026, 6, 6),
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

        OutgoingMessage outgoing = scenario.handle(
                telegram("нет"),
                BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                "нет"
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(storedDraft.get().seatingPreference()).isNull();
        assertThat(storedDraft.get().seatingPreferenceResolved()).isTrue();
        verify(tableReservationService).createReservation(any(TableReservationCommand.class));
    }

    @Test
    void storesSeatingPreferenceBeforeCreatingReservation() {
        AtomicReference<TableBookingDraftStorage.Draft> storedDraft = new AtomicReference<>(new TableBookingDraftStorage.Draft(
                "AERIS",
                Instant.parse("2026-06-06T15:00:00Z"),
                Instant.parse("2026-06-06T17:00:00Z"),
                LocalDate.of(2026, 6, 6),
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

        OutgoingMessage outgoing = scenario.handle(
                telegram("тихий стол не у прохода"),
                BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                "тихий стол не у прохода"
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(storedDraft.get().seatingPreference()).contains("тихий стол");
        assertThat(storedDraft.get().seatingPreferenceResolved()).isTrue();
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

        OutgoingMessage outgoing = scenario.handle(
                telegram("Возможно будет четыре"),
                BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                "Возможно будет четыре"
        );

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(storedDraft.get().seatingPreference()).isEqualTo("возможно будет четыре");
        assertThat(storedDraft.get().seatingPreferenceResolved()).isTrue();
        var commandCaptor = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(commandCaptor.capture());
        assertThat(commandCaptor.getValue().seatingPreference()).isEqualTo("возможно будет четыре");
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
        Instant start = Instant.parse("2026-06-06T15:00:00Z");
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
