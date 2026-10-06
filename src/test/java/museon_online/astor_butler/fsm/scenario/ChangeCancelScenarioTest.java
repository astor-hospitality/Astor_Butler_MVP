package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.booking.EventBookingOrder;
import museon_online.astor_butler.domain.booking.EventBookingService;
import museon_online.astor_butler.domain.booking.EventBookingStatus;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.InputIntent;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeCancelScenarioTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private TableReservationService tableReservationService;

    @Mock
    private EventBookingService eventBookingService;

    @Mock
    private ChangeCancelDraftStorage changeDraftStorage;

    private ChangeCancelScenario scenario;

    @BeforeEach
    void setUp() {
        lenient().when(changeDraftStorage.find(anyLong())).thenReturn(Optional.empty());
        scenario = new ChangeCancelScenario(
                fsmStorage,
                tableReservationService,
                eventBookingService,
                changeDraftStorage,
                new museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService(),
                new BookingTimeProvider()
        );
        ReflectionTestUtils.setField(scenario, "adminChatId", "100500");
    }

    @Test
    void asksForReferenceBeforeChangingBooking() {
        IncomingMessage incoming = telegram("отменить бронь");
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId())).thenReturn(List.of());
        when(eventBookingService.listActiveOrdersByChatId(incoming.chatId())).thenReturn(List.of());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_CHANGE_REQUESTED.name());
        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly("CHANGE_CANCEL", "ASK_ACTIVE_ORDER_REFERENCE");
        assertThat(outgoing.text()).contains("дату", "время", "номер заявки");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
    }

    @Test
    void showsActiveReservationsBeforeAskingReference() {
        IncomingMessage incoming = telegram("отменить бронь");
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId()))
                .thenReturn(List.of(activeReservation()));
        when(eventBookingService.listActiveOrdersByChatId(incoming.chatId())).thenReturn(List.of());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_CHANGE_REQUESTED.name());
        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly(
                "CHANGE_CANCEL",
                "ACTIVE_RESERVATIONS_FOUND",
                "ASK_ACTIVE_ORDER_REFERENCE"
        );
        assertThat(outgoing.text()).contains(
                "Бронь подтверждена",
                "Заказ: #44",
                "Стол: Окно у бара (A7)",
                "Дата: 18.06.2026",
                "Время: 20:00 - 22:00",
                "Выберите действие кнопкой"
        );
        assertThat(outgoing.metadata()).containsEntry("activeReservationIds", List.of(44L));
        assertThat(outgoing.metadata()).containsKey("replyKeyboardRows");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
    }

    @Test
    void showsActiveEventOrdersBeforeAskingReference() {
        IncomingMessage incoming = telegram("перенести мероприятие");
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId())).thenReturn(List.of());
        when(eventBookingService.listActiveOrdersByChatId(incoming.chatId()))
                .thenReturn(List.of(activeEvent()));

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_CHANGE_REQUESTED.name());
        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly(
                "CHANGE_CANCEL",
                "ACTIVE_RESERVATIONS_FOUND",
                "ASK_ACTIVE_ORDER_REFERENCE"
        );
        assertThat(outgoing.text()).contains("мероприятие #88", "CORPORATE", "2026-06-21", "Выберите действие кнопкой");
        assertThat(outgoing.metadata()).containsEntry("activeEventOrderIds", List.of(88L));
        assertThat(outgoing.metadata()).containsKey("replyKeyboardRows");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
    }

    @Test
    void sendsAdminAlertWhenReferenceIsProvided() {
        IncomingMessage incoming = telegram("бронь завтра на 20:00, надо перенести");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.adminAlert().chatId()).isEqualTo("100500");
        assertThat(outgoing.adminAlert().text()).contains("Astor Butler / change or cancel", "бронь завтра на 20:00");
        assertThat(outgoing.actions()).containsExactly("CHANGE_CANCEL", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void cancelsReferencedActiveTableReservation() {
        IncomingMessage incoming = telegram("отмени бронь #44");
        TableReservationOrder active = activeReservation();
        TableReservationOrder cancelled = cancelledReservation();
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId()))
                .thenReturn(List.of(active));
        when(tableReservationService.cancelByGuest(44L)).thenReturn(cancelled);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.text()).contains("отменил бронь стола #44", "Окно у бара (A7)", "освободил слот");
        assertThat(outgoing.actions()).containsExactly(
                "CHANGE_CANCEL",
                "TABLE_RESERVATION_CANCELLED",
                "HOLD_RELEASED",
                "RETURN_MAIN_MENU"
        );
        assertThat(outgoing.metadata()).containsEntry("cancelledTableReservationId", 44L);
        verify(tableReservationService).cancelByGuest(44L);
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void cancelsSingleActiveTableReservationFromActionButton() {
        IncomingMessage incoming = telegram("❌ Отменить стол");
        TableReservationOrder active = activeReservation();
        TableReservationOrder cancelled = cancelledReservation();
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId()))
                .thenReturn(List.of(active));
        when(eventBookingService.listActiveOrdersByChatId(incoming.chatId())).thenReturn(List.of());
        when(tableReservationService.cancelByGuest(44L)).thenReturn(cancelled);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.text()).contains("бронь #44 отменил", "освободил стол", "Главное меню");
        assertThat(outgoing.actions()).containsExactly(
                "CHANGE_CANCEL",
                "TABLE_RESERVATION_CANCELLED",
                "HOLD_RELEASED",
                "RETURN_MAIN_MENU"
        );
        assertThat(outgoing.metadata()).containsEntry("cancelledTableReservationId", 44L);
        verify(tableReservationService).cancelByGuest(44L);
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void cancelsReferencedActiveEventBooking() {
        IncomingMessage incoming = telegram("отмени заявку #88");
        EventBookingOrder active = activeEvent();
        EventBookingOrder cancelled = cancelledEvent();
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId()))
                .thenReturn(List.of());
        when(eventBookingService.listActiveOrdersByChatId(incoming.chatId()))
                .thenReturn(List.of(active));
        when(eventBookingService.cancelByGuest(88L)).thenReturn(cancelled);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.adminAlert().chatId()).isEqualTo("100500");
        assertThat(outgoing.text()).contains("event-заявку #88", "CORPORATE", "2026-06-21");
        assertThat(outgoing.actions()).containsExactly(
                "CHANGE_CANCEL",
                "EVENT_BOOKING_CANCELLED",
                "ADMIN_ALERT",
                "RETURN_MAIN_MENU"
        );
        assertThat(outgoing.metadata()).containsEntry("cancelledEventBookingId", 88L);
        verify(eventBookingService).cancelByGuest(88L);
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void changesTimeFromPendingActionAndReturnsToMainMenu() {
        IncomingMessage incoming = telegram("17:30");
        TableReservationOrder active = activeReservation();
        TableReservationOrder changed = changedReservation(
                Instant.parse("2026-06-18T12:30:00Z"),
                Instant.parse("2026-06-18T14:30:00Z"),
                2,
                "A7",
                "Окно у бара"
        );
        when(changeDraftStorage.find(incoming.chatId()))
                .thenReturn(Optional.of(new ChangeCancelDraftStorage.Draft(44L, "CHANGE_TIME")));
        when(tableReservationService.getReservation(44L)).thenReturn(active);
        when(tableReservationService.changeByGuest(eq(44L), any())).thenReturn(changed);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, incoming.text());

        var captor = forClass(museon_online.astor_butler.domain.booking.TableReservationChangeCommand.class);
        verify(tableReservationService).changeByGuest(eq(44L), captor.capture());
        assertThat(captor.getValue().requestedStartAt()).isEqualTo(Instant.parse("2026-06-18T12:30:00Z"));
        assertThat(captor.getValue().requestedEndAt()).isEqualTo(Instant.parse("2026-06-18T14:30:00Z"));
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.text()).contains("Принял", "повторное подтверждение", "17:30 - 19:30");
        assertThat(outgoing.removeKeyboard()).isTrue();
        verify(changeDraftStorage).clear(incoming.chatId());
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void theMenuButtonOpensTheGuestsBooking() {
        // The reply keyboard sends its label as the message text, and the router asks the scenario with the understood text.
        IncomingMessage incoming = telegram("Изменить / отменить");
        UnderstoodInput understood = new GuestInputUnderstandingService().understand(incoming.text(), BotState.READY_FOR_DIALOG);
        lenient().when(tableReservationService.listActiveReservationsByChatId(incoming.chatId())).thenReturn(List.of(activeReservation()));
        lenient().when(eventBookingService.listActiveOrdersByChatId(incoming.chatId())).thenReturn(List.of());

        assertThat(understood.primaryIntent()).isEqualTo(InputIntent.CHANGE_CANCEL);
        assertThat(scenario.supports(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood)).isTrue();

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood);

        assertThat(outgoing.actions()).containsExactly("CHANGE_CANCEL", "ACTIVE_RESERVATIONS_FOUND", "ASK_ACTIVE_ORDER_REFERENCE");
        assertThat(outgoing.text()).contains("Заказ: #44", "Выберите действие кнопкой");
        assertThat(outgoing.metadata()).containsKey("replyKeyboardRows");
        verify(tableReservationService, never()).cancelByGuest(anyLong());
    }

    @Test
    void aRequestTheHostessHasNotAnsweredIsNotCalledConfirmed() {
        IncomingMessage incoming = telegram("отменить бронь");
        when(tableReservationService.listActiveReservationsByChatId(incoming.chatId())).thenReturn(List.of(awaitingReservation()));
        when(eventBookingService.listActiveOrdersByChatId(incoming.chatId())).thenReturn(List.of());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.text()).contains("Заявка ждет подтверждения", "Заказ: #44", "Стол: Окно у бара (A7)");
        assertThat(outgoing.text()).doesNotContain("Бронь подтверждена", "Ваш стол ждет вас");
    }

    @Test
    void afterAChangeTheCardSaysTheRequestWaitsForTheHostessAgain() {
        IncomingMessage incoming = telegram("17:30");
        when(changeDraftStorage.find(incoming.chatId()))
                .thenReturn(Optional.of(new ChangeCancelDraftStorage.Draft(44L, "CHANGE_TIME")));
        when(tableReservationService.getReservation(44L)).thenReturn(activeReservation());
        when(tableReservationService.changeByGuest(eq(44L), any())).thenReturn(awaitingReservation());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, incoming.text());

        assertThat(outgoing.text()).contains("повторное подтверждение", "Заявка ждет подтверждения");
        assertThat(outgoing.text()).doesNotContain("Бронь подтверждена", "Ваш стол ждет вас");
    }

    @Test
    void movesTheBookingToTheTimeOfDayTheGuestNames() {
        // The booking is on 18.06 in Yekaterinburg, five hours ahead of UTC.
        String[][] cases = {{"7:30 вечера", "2026-06-18T14:30:00Z"}, {"в 7 часов вечера", "2026-06-18T14:00:00Z"}, {"в 2 часа дня", "2026-06-18T09:00:00Z"}};
        for (String[] example : cases) {
            IncomingMessage incoming = telegram(example[0]);
            UnderstoodInput understood = new GuestInputUnderstandingService().understand(incoming.text(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
            when(changeDraftStorage.find(incoming.chatId()))
                    .thenReturn(Optional.of(new ChangeCancelDraftStorage.Draft(44L, "CHANGE_TIME")));
            when(tableReservationService.getReservation(44L)).thenReturn(activeReservation());
            when(tableReservationService.changeByGuest(eq(44L), any())).thenReturn(awaitingReservation());

            scenario.handle(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, understood.routeText(), understood);

            var captor = forClass(museon_online.astor_butler.domain.booking.TableReservationChangeCommand.class);
            verify(tableReservationService, atLeastOnce()).changeByGuest(eq(44L), captor.capture());
            assertThat(captor.getValue().requestedStartAt()).as(example[0]).isEqualTo(Instant.parse(example[1]));
        }
    }

    @Test
    void asksForTheDateAgainWhenTheDayDoesNotExist() {
        IncomingMessage incoming = telegram("32.13");
        when(changeDraftStorage.find(incoming.chatId()))
                .thenReturn(Optional.of(new ChangeCancelDraftStorage.Draft(44L, "CHANGE_DATE")));
        when(tableReservationService.getReservation(44L)).thenReturn(activeReservation());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, incoming.text());

        verify(tableReservationService, never()).changeByGuest(anyLong(), any());
        assertThat(outgoing.text()).isNotBlank();
    }

    @Test
    void changesPartySizeFromPendingActionAndReturnsToMainMenu() {
        IncomingMessage incoming = telegram("на пятерых");
        TableReservationOrder active = activeReservation();
        TableReservationOrder changed = changedReservation(
                active.requestedStartAt(),
                active.requestedEndAt(),
                5,
                "11",
                "Стол 11 · камерная гостиная"
        );
        when(changeDraftStorage.find(incoming.chatId()))
                .thenReturn(Optional.of(new ChangeCancelDraftStorage.Draft(44L, "CHANGE_PARTY_SIZE")));
        when(tableReservationService.getReservation(44L)).thenReturn(active);
        when(tableReservationService.changeByGuest(eq(44L), any())).thenReturn(changed);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, incoming.text());

        var captor = forClass(museon_online.astor_butler.domain.booking.TableReservationChangeCommand.class);
        verify(tableReservationService).changeByGuest(eq(44L), captor.capture());
        assertThat(captor.getValue().partySize()).isEqualTo(5);
        assertThat(outgoing.text()).contains("Гостей: 5", "Стол 11");
        assertThat(outgoing.actions()).contains("RESERVATION_CHANGED");
        verify(changeDraftStorage).clear(incoming.chatId());
    }

    @Test
    void supportsChangeCancelIntentAndContinuation() {
        IncomingMessage incoming = telegram("перенести бронь");

        assertThat(scenario.supports(incoming, BotState.READY_FOR_DIALOG, incoming.text())).isTrue();
        assertThat(scenario.supports(incoming, BotState.TABLE_BOOKING_CHANGE_REQUESTED, "завтра 20:00")).isTrue();
        assertThat(scenario.supports(incoming, BotState.TABLE_BOOKING_COLLECT_TIME, "завтра 20:00")).isFalse();
    }

    private IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(
                1773317437L,
                1773317437L,
                351,
                284069875,
                text,
                null,
                "Наталья",
                "Поединенко",
                "Poedinenko",
                "ru",
                false,
                "284069875"
        );
    }

    private TableReservationOrder activeReservation() {
        return new TableReservationOrder(
                44L,
                1773317437L,
                1773317437L,
                777L,
                7L,
                "A7",
                "Окно у бара",
                null,
                null,
                TableReservationStatus.CONFIRMED,
                "TELEGRAM",
                Instant.parse("2026-06-18T15:00:00Z"),
                Instant.parse("2026-06-18T17:00:00Z"),
                2,
                "Наталья Поединенко",
                null,
                null,
                876857557L,
                null,
                "-1004291419562",
                null,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private TableReservationOrder awaitingReservation() {
        TableReservationOrder active = activeReservation();
        return changedReservation(active.requestedStartAt(), active.requestedEndAt(), active.partySize(), active.tableCode(), active.tableDisplayName());
    }

    private TableReservationOrder cancelledReservation() {
        return new TableReservationOrder(
                44L,
                1773317437L,
                1773317437L,
                777L,
                7L,
                "A7",
                "Окно у бара",
                null,
                null,
                TableReservationStatus.CANCELLED,
                "TELEGRAM",
                Instant.parse("2026-06-18T15:00:00Z"),
                Instant.parse("2026-06-18T17:00:00Z"),
                2,
                "Наталья Поединенко",
                "+79991234567",
                null,
                876857557L,
                null,
                "-1004291419562",
                null,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private TableReservationOrder changedReservation(
            Instant startAt,
            Instant endAt,
            Integer partySize,
            String tableCode,
            String displayName
    ) {
        return new TableReservationOrder(
                44L,
                1773317437L,
                1773317437L,
                777L,
                7L,
                tableCode,
                displayName,
                null,
                null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                "TELEGRAM",
                startAt,
                endAt,
                partySize,
                "Наталья Поединенко",
                "+79991234567",
                null,
                876857557L,
                null,
                "-1004291419562",
                null,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private EventBookingOrder activeEvent() {
        return new EventBookingOrder(
                88L,
                1773317437L,
                1773317437L,
                null,
                "AERIS",
                EventBookingStatus.AWAITING_MANAGER_REVIEW,
                "TELEGRAM",
                "CORPORATE",
                LocalDate.parse("2026-06-21"),
                "19:00",
                30,
                null,
                null,
                null,
                null,
                "Наталья Поединенко",
                null,
                "корпоратив на 30 гостей",
                876857557L,
                null,
                "100500",
                null,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private EventBookingOrder cancelledEvent() {
        return new EventBookingOrder(
                88L,
                1773317437L,
                1773317437L,
                null,
                "AERIS",
                EventBookingStatus.CANCELLED,
                "TELEGRAM",
                "CORPORATE",
                LocalDate.parse("2026-06-21"),
                "19:00",
                30,
                null,
                null,
                null,
                null,
                "Наталья Поединенко",
                "+79991234567",
                "корпоратив на 30 гостей",
                876857557L,
                null,
                "100500",
                null,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }
}
