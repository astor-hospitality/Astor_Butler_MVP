package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.booking.EventBookingOrder;
import museon_online.astor_butler.domain.booking.EventBookingService;
import museon_online.astor_butler.domain.booking.TableReservationChangeCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.SlotValue;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ChangeCancelScenario implements FsmScenario {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.of("Asia/Yekaterinburg"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.of("Asia/Yekaterinburg"));
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Asia/Yekaterinburg"));
    private static final Pattern EXPLICIT_TIME = Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b");
    private static final Pattern SHORT_HOUR = Pattern.compile("^(?:в\\s+|к\\s+)?([1-9]|1[0-1]|1\\d|2[0-3])$");

    private final FSMStorage fsmStorage;
    private final TableReservationService tableReservationService;
    private final EventBookingService eventBookingService;
    private final ChangeCancelDraftStorage changeDraftStorage;
    private final GuestInputUnderstandingService understandingService;
    private final BookingTimeProvider timeProvider;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    @Override
    public String id() {
        return "CHANGE_CANCEL";
    }

    @Override
    public int priority() {
        return 34;
    }

    @Override
    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        return owns(state) || isChangeCancelIntent(normalized);
    }

    @Override
    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        OutgoingMessage cancellation = tryCancelActiveOrder(incoming, currentState, text, normalized);
        if (cancellation != null) {
            return cancellation;
        }
        OutgoingMessage pendingChange = tryApplyPendingChange(incoming, normalized);
        if (pendingChange != null) {
            return pendingChange;
        }
        OutgoingMessage activeOrderAction = tryHandleActiveOrderAction(incoming, normalized);
        if (activeOrderAction != null) {
            return activeOrderAction;
        }
        if (state == BotState.TABLE_BOOKING_CHANGE_REQUESTED || containsReference(normalized)) {
            return sendChangeCancelRequest(incoming, currentState, text);
        }

        List<TableReservationOrder> activeReservations = tableReservationService.listActiveReservationsByChatId(incoming.chatId());
        List<EventBookingOrder> activeEvents = eventBookingService.listActiveOrdersByChatId(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
        if (!activeReservations.isEmpty() || !activeEvents.isEmpty()) {
            int activeCount = activeReservations.size() + activeEvents.size();
            Long selectedTableOrderId = activeCount == 1 && !activeReservations.isEmpty() ? activeReservations.getFirst().id() : null;
            changeDraftStorage.save(incoming.chatId(), new ChangeCancelDraftStorage.Draft(selectedTableOrderId, ""));
            return OutgoingMessage.of(
                    incoming,
                    activeCount == 1
                            ? singleActiveOrderText(activeReservations, activeEvents)
                            : multipleActiveOrdersText(activeReservations, activeEvents),
                    BotState.TABLE_BOOKING_CHANGE_REQUESTED.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("CHANGE_CANCEL", "ACTIVE_RESERVATIONS_FOUND", "ASK_ACTIVE_ORDER_REFERENCE")
            ).withMetadata(Map.of(
                    "scenario", id(),
                    "activeReservationIds", activeReservations.stream().map(TableReservationOrder::id).toList(),
                    "activeEventOrderIds", activeEvents.stream().map(EventBookingOrder::id).toList(),
                    "replyKeyboardRows", activeCount == 1
                            ? changeActionRows()
                            : activeOrderSelectionRows(activeReservations, activeEvents)
            ));
        }
        return OutgoingMessage.of(
                incoming,
                "Понял, нужно изменить или отменить бронь. Напишите, пожалуйста, дату, время или номер заявки — я передам команде точный запрос и не сниму бронь без подтверждения.",
                BotState.TABLE_BOOKING_CHANGE_REQUESTED.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("CHANGE_CANCEL", "ASK_ACTIVE_ORDER_REFERENCE")
        ).withMetadata(Map.of("scenario", id()));
    }

    private String activeRequestsText(List<TableReservationOrder> reservations, List<EventBookingOrder> events) {
        List<String> lines = new java.util.ArrayList<>();
        reservations.stream()
                .limit(5)
                .map(this::activeReservationText)
                .forEach(lines::add);
        events.stream()
                .limit(5)
                .map(this::activeEventText)
                .forEach(lines::add);
        if (lines.isEmpty()) {
            return "активных заявок не найдено";
        }
        return String.join("\n", lines);
    }

    private String singleActiveOrderText(List<TableReservationOrder> reservations, List<EventBookingOrder> events) {
        if (!reservations.isEmpty()) {
            return """
                    Нашел вашу активную бронь:

                    %s

                    Что меняем? Выберите действие кнопкой ниже. Без вашего явного выбора я ничего не отменю и не перенесу.
                    """.formatted(confirmedReservationCard(reservations.getFirst()));
        }
        return """
                Нашел активную заявку на мероприятие:

                %s

                Что меняем? Выберите действие кнопкой ниже.
                """.formatted(activeEventText(events.getFirst()));
    }

    private String multipleActiveOrdersText(List<TableReservationOrder> reservations, List<EventBookingOrder> events) {
        return """
                Нашел несколько активных заявок:

                %s

                Выберите нужную заявку кнопкой ниже, а потом действие.
                """.formatted(activeRequestsText(reservations, events));
    }

    private String activeReservationText(TableReservationOrder order) {
        return "- бронь #%s: %s, %s, гостей: %s, статус: %s".formatted(
                order.id(),
                tableName(order),
                order.requestedStartAt() == null ? "время не указано" : DATE_TIME.format(order.requestedStartAt()),
                order.partySize() == null ? "не указано" : order.partySize(),
                order.status()
        );
    }

    private String confirmedReservationCard(TableReservationOrder order) {
        return """
                Бронь подтверждена

                Ваш стол ждет вас.

                Заказ: #%s
                Стол: %s
                Дата: %s
                Время: %s
                Гостей: %s
                Пожелание: %s
                """.formatted(
                order.id(),
                tableName(order),
                order.requestedStartAt() == null ? "не указана" : java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Asia/Yekaterinburg")).format(order.requestedStartAt()),
                timeRange(order),
                order.partySize() == null ? "не указано" : order.partySize(),
                seatingPreference(order)
        );
    }

    private String activeEventText(EventBookingOrder order) {
        return "- мероприятие #%s: %s, %s, гостей: %s, статус: %s".formatted(
                order.id(),
                order.eventType() == null || order.eventType().isBlank() ? "формат уточняется" : order.eventType(),
                order.requestedDate() == null ? "дата уточняется" : order.requestedDate(),
                order.guestCount() == null ? "не указано" : order.guestCount(),
                order.status()
        );
    }

    private String tableName(TableReservationOrder order) {
        if (order.tableCode() == null || order.tableCode().isBlank()) {
            return "стол не выбран";
        }
        if (order.tableDisplayName() == null || order.tableDisplayName().isBlank()) {
            return "стол " + order.tableCode();
        }
        return humanTableDisplayName(order.tableDisplayName()) + " (" + order.tableCode() + ")";
    }

    private String humanTableDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.startsWith("Table ")) {
            return "Стол " + value.substring("Table ".length());
        }
        if (value.startsWith("VIP ")) {
            return "Стол VIP " + value.substring("VIP ".length());
        }
        return value;
    }

    private String seatingPreference(TableReservationOrder order) {
        if (order.seatingPreference() != null && !order.seatingPreference().isBlank()) {
            return order.seatingPreference();
        }
        if (order.preferredZone() != null && !order.preferredZone().isBlank()) {
            return order.preferredZone();
        }
        return "нет";
    }

    private String timeRange(TableReservationOrder order) {
        if (order.requestedStartAt() == null || order.requestedEndAt() == null) {
            return "не указано";
        }
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Yekaterinburg"));
        return time.format(order.requestedStartAt()) + " - " + time.format(order.requestedEndAt());
    }

    private List<List<String>> changeActionRows() {
        return List.of(
                List.of("❌ Отменить стол", "👥 Изменить гостей"),
                List.of("🪑 Изменить стол", "🕰 Перенести время"),
                List.of("📅 Перенести дату", "↩️ Отменить действие")
        );
    }

    private List<List<String>> activeOrderSelectionRows(List<TableReservationOrder> reservations, List<EventBookingOrder> events) {
        List<String> labels = new java.util.ArrayList<>();
        reservations.stream()
                .limit(6)
                .map(order -> "Заказ #" + order.id() + " · стол " + blank(order.tableCode(), "?"))
                .forEach(labels::add);
        events.stream()
                .limit(6)
                .map(order -> "Заявка #" + order.id() + " · мероприятие")
                .forEach(labels::add);
        List<List<String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < labels.size(); i += 2) {
            rows.add(List.copyOf(labels.subList(i, Math.min(i + 2, labels.size()))));
        }
        rows.add(List.of("↩️ Отменить действие"));
        return rows;
    }

    private List<List<String>> timeRows() {
        return List.of(
                List.of("12:00", "13:00", "14:00", "15:00"),
                List.of("16:00", "17:00", "18:00", "19:00"),
                List.of("20:00", "21:00", "22:00", "23:00"),
                List.of("↩️ Отменить действие")
        );
    }

    private List<List<String>> dateRows() {
        java.time.LocalDate today = java.time.LocalDate.now(ZoneId.of("Asia/Yekaterinburg"));
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            java.time.LocalDate date = today.plusDays(i);
            labels.add((i == 0 ? "Сегодня " : i == 1 ? "Завтра " : "") + date.format(DateTimeFormatter.ofPattern("dd.MM")));
        }
        List<List<String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < labels.size(); i += 3) {
            rows.add(List.copyOf(labels.subList(i, Math.min(i + 3, labels.size()))));
        }
        rows.add(List.of("↩️ Отменить действие"));
        return rows;
    }

    private boolean ownsTextAction(String text) {
        return containsAny(text, "отменить стол", "изменить гостей", "изменить стол", "перенести время", "перенести дату", "отменить действие")
                || containsAny(text, "количество гостей", "другой стол", "другое время", "другая дата");
    }

    private String actionFromText(String text) {
        if (isCancelAction(text)) {
            return "CANCEL_TABLE";
        }
        if (containsAny(text, "гост", "колич", "человек")) {
            return "CHANGE_PARTY_SIZE";
        }
        if (containsAny(text, "стол", "мест", "зон")) {
            return "CHANGE_TABLE";
        }
        if (containsAny(text, "врем", "час")) {
            return "CHANGE_TIME";
        }
        if (containsAny(text, "дат", "день")) {
            return "CHANGE_DATE";
        }
        return "";
    }

    private boolean isCancelAction(String text) {
        return containsAny(text, "отменить стол", "отменить брон", "отмена брон", "не придем", "не придём", "не сможем");
    }

    private OutgoingMessage tryCancelActiveOrder(
            IncomingMessage incoming,
            BotState previousState,
            String text,
            String normalized
    ) {
        if (!isCancelIntent(normalized)) {
            return null;
        }
        Long requestedId = referencedId(normalized);
        if (requestedId == null) {
            return null;
        }

        List<TableReservationOrder> activeReservations = tableReservationService.listActiveReservationsByChatId(incoming.chatId());
        for (TableReservationOrder order : activeReservations) {
            if (requestedId.equals(order.id())) {
                TableReservationOrder cancelled = tableReservationService.cancelByGuest(order.id());
                fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
                return OutgoingMessage.of(
                        incoming,
                        """
                                Готово. Я отменил бронь стола #%s и освободил слот.

                                %s
                                %s - %s

                                Если нужен новый стол или другое время, напишите новый запрос.
                                """.formatted(
                                cancelled.id(),
                                tableName(cancelled),
                                cancelled.requestedStartAt() == null ? "время не указано" : DATE_TIME.format(cancelled.requestedStartAt()),
                                cancelled.requestedEndAt() == null ? "окончание не указано" : DATE_TIME.format(cancelled.requestedEndAt())
                        ),
                        BotState.READY_FOR_DIALOG.name(),
                        false,
                        false,
                        true,
                        false,
                        AdminAlert.none(),
                        List.of("CHANGE_CANCEL", "TABLE_RESERVATION_CANCELLED", "HOLD_RELEASED", "RETURN_MAIN_MENU")
                ).withMetadata(Map.of(
                        "scenario", id(),
                        "cancelledTableReservationId", cancelled.id()
                ));
            }
        }

        List<EventBookingOrder> activeEvents = eventBookingService.listActiveOrdersByChatId(incoming.chatId());
        for (EventBookingOrder order : activeEvents) {
            if (requestedId.equals(order.id())) {
                EventBookingOrder cancelled = eventBookingService.cancelByGuest(order.id());
                fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
                return OutgoingMessage.of(
                        incoming,
                        """
                                Готово. Я отметил event-заявку #%s как отмененную.

                                Формат: %s
                                Дата: %s

                                Команда увидит изменение в журнале. Если нужно создать новую заявку, напишите детали заново.
                                """.formatted(
                                cancelled.id(),
                                cancelled.eventType() == null || cancelled.eventType().isBlank() ? "уточнялся" : cancelled.eventType(),
                                cancelled.requestedDate() == null ? "уточнялась" : cancelled.requestedDate()
                        ),
                        BotState.READY_FOR_DIALOG.name(),
                        false,
                        false,
                        true,
                        false,
                        adminAlert(incoming, previousState, text),
                        List.of("CHANGE_CANCEL", "EVENT_BOOKING_CANCELLED", "ADMIN_ALERT", "RETURN_MAIN_MENU")
                ).withMetadata(Map.of(
                        "scenario", id(),
                        "cancelledEventBookingId", cancelled.id()
                ));
            }
        }

        return null;
    }

    private OutgoingMessage tryHandleActiveOrderAction(IncomingMessage incoming, String normalized) {
        if (!ownsTextAction(normalized)) {
            return null;
        }
        if (containsAny(normalized, "отменить действие", "ничего", "назад", "главное", "оставить как есть")) {
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            changeDraftStorage.clear(incoming.chatId());
            return OutgoingMessage.of(
                    incoming,
                    "Хорошо, ничего не меняю. Бронь остается как была, ждем вас в AERIS.",
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("CHANGE_CANCEL", "ACTION_CANCELLED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of("scenario", id()));
        }

        List<TableReservationOrder> activeReservations = tableReservationService.listActiveReservationsByChatId(incoming.chatId());
        List<EventBookingOrder> activeEvents = eventBookingService.listActiveOrdersByChatId(incoming.chatId());
        String action = actionFromText(normalized);
        if (activeReservations.size() + activeEvents.size() > 1 && !action.isBlank()) {
            changeDraftStorage.save(incoming.chatId(), new ChangeCancelDraftStorage.Draft(null, action));
            fsmStorage.setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
            return OutgoingMessage.of(
                    incoming,
                    "У вас несколько активных заявок. Выберите, пожалуйста, какую именно меняем.",
                    BotState.TABLE_BOOKING_CHANGE_REQUESTED.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("CHANGE_CANCEL", action, "ASK_ORDER_SELECTION")
            ).withMetadata(Map.of(
                    "scenario", id(),
                    "replyKeyboardRows", activeOrderSelectionRows(activeReservations, activeEvents)
            ));
        }
        if (activeReservations.size() + activeEvents.size() != 1 || activeReservations.isEmpty()) {
            return null;
        }

        TableReservationOrder order = activeReservations.getFirst();
        if (isCancelAction(normalized)) {
            TableReservationOrder cancelled = tableReservationService.cancelByGuest(order.id());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    """
                    Готово, бронь #%s отменил и освободил стол. Команда AERIS уже увидит отмену.

                    Будем рады вас видеть в другой день. Главное меню оставил под рукой.
                    """.formatted(cancelled.id()),
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("CHANGE_CANCEL", "TABLE_RESERVATION_CANCELLED", "HOLD_RELEASED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of("scenario", id(), "cancelledTableReservationId", cancelled.id()));
        }
        if (!action.isBlank()) {
            return startChangeAction(incoming, order.id(), action);
        }
        return null;
    }

    private OutgoingMessage startChangeAction(IncomingMessage incoming, Long orderId, String action) {
        changeDraftStorage.save(incoming.chatId(), new ChangeCancelDraftStorage.Draft(orderId, action));
        return switch (action) {
            case "CHANGE_PARTY_SIZE" -> changeWorkInProgress(
                    incoming,
                    "Понял, меняем количество гостей. Напишите новое число: например, «на четверых» или просто «4».",
                    action,
                    null
            );
            case "CHANGE_TABLE" -> changeWorkInProgress(
                    incoming,
                    "Понял, выбираем другой стол. Напишите номер стола, зону или «подбери сам».",
                    action,
                    null
            );
            case "CHANGE_TIME" -> changeWorkInProgress(
                    incoming,
                    "Понял, переносим время. Выберите новое время кнопкой или напишите в формате 17:30.",
                    action,
                    timeRows()
            );
            case "CHANGE_DATE" -> changeWorkInProgress(
                    incoming,
                    "Понял, переносим дату. Выберите новый день кнопкой или напишите его сообщением.",
                    action,
                    dateRows()
            );
            default -> null;
        };
    }

    private OutgoingMessage changeWorkInProgress(IncomingMessage incoming, String text, String action, List<List<String>> rows) {
        fsmStorage.setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
        OutgoingMessage outgoing = OutgoingMessage.of(
                incoming,
                text,
                BotState.TABLE_BOOKING_CHANGE_REQUESTED.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("CHANGE_CANCEL", action)
        ).withMetadata(Map.of("scenario", id()));
        return rows == null || rows.isEmpty() ? outgoing : outgoing.withMetadata(Map.of("replyKeyboardRows", rows));
    }

    private OutgoingMessage tryApplyPendingChange(IncomingMessage incoming, String normalized) {
        Optional<ChangeCancelDraftStorage.Draft> draft = changeDraftStorage.find(incoming.chatId());
        if (draft.isEmpty()) {
            return null;
        }
        ChangeCancelDraftStorage.Draft pending = draft.get();
        if (containsAny(normalized, "отменить действие", "ничего", "назад", "главное", "оставить как есть")) {
            changeDraftStorage.clear(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    "Хорошо, ничего не меняю. Бронь остается как была, ждем вас в AERIS.",
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("CHANGE_CANCEL", "ACTION_CANCELLED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of("scenario", id()));
        }

        if (pending.tableReservationId() == null) {
            Long selectedId = referencedId(normalized);
            if (selectedId == null) {
                return null;
            }
            List<TableReservationOrder> activeReservations = tableReservationService.listActiveReservationsByChatId(incoming.chatId());
            Optional<TableReservationOrder> selected = activeReservations.stream()
                    .filter(order -> selectedId.equals(order.id()))
                    .findFirst();
            if (selected.isEmpty()) {
                return null;
            }
            if (pending.action() == null || pending.action().isBlank()) {
                changeDraftStorage.save(incoming.chatId(), new ChangeCancelDraftStorage.Draft(selectedId, ""));
                fsmStorage.setState(incoming.chatId(), BotState.TABLE_BOOKING_CHANGE_REQUESTED);
                return OutgoingMessage.of(
                        incoming,
                        "Нашел эту бронь.\n\n%s\n\nЧто меняем?".formatted(confirmedReservationCard(selected.get())),
                        BotState.TABLE_BOOKING_CHANGE_REQUESTED.name(),
                        false,
                        false,
                        false,
                        false,
                        AdminAlert.none(),
                        List.of("CHANGE_CANCEL", "ORDER_SELECTED", "ASK_CHANGE_ACTION")
                ).withMetadata(Map.of("scenario", id(), "replyKeyboardRows", changeActionRows()));
            }
            return startChangeAction(incoming, selectedId, pending.action());
        }

        if (pending.action() == null || pending.action().isBlank()) {
            String action = actionFromText(normalized);
            return action.isBlank() ? null : startChangeAction(incoming, pending.tableReservationId(), action);
        }

        TableReservationOrder current = tableReservationService.getReservation(pending.tableReservationId());
        TableReservationChangeCommand command = switch (pending.action()) {
            case "CHANGE_PARTY_SIZE" -> changePartySizeCommand(incoming, normalized, current).orElse(null);
            case "CHANGE_TABLE" -> changeTableCommand(incoming, normalized, current).orElse(null);
            case "CHANGE_TIME" -> changeTimeCommand(incoming, normalized, current).orElse(null);
            case "CHANGE_DATE" -> changeDateCommand(incoming, normalized, current).orElse(null);
            default -> null;
        };
        if (command == null) {
            return askAgainForPending(incoming, pending.action());
        }

        TableReservationOrder changed = tableReservationService.changeByGuest(current.id(), command);
        changeDraftStorage.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                """
                Принял, обновил бронь и отправил команде AERIS на повторное подтверждение.

                %s

                Как только хостес ответит, я вернусь с финальным статусом. Главное меню оставил под рукой.
                """.formatted(confirmedReservationCard(changed)),
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("CHANGE_CANCEL", pending.action(), "RESERVATION_CHANGED", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of("scenario", id(), "changedTableReservationId", changed.id()));
    }

    private OutgoingMessage askAgainForPending(IncomingMessage incoming, String action) {
        return switch (action) {
            case "CHANGE_PARTY_SIZE" -> changeWorkInProgress(
                    incoming,
                    "Я рядом, просто не хочу ошибиться с количеством гостей. Напишите число: например, «на двоих», «на троих» или «4».",
                    action,
                    null
            );
            case "CHANGE_TABLE" -> changeWorkInProgress(
                    incoming,
                    "Не смог уверенно понять стол. Напишите номер стола, зону: «у окна», «винная комната», «у бара», или «подбери сам».",
                    action,
                    null
            );
            case "CHANGE_TIME" -> changeWorkInProgress(
                    incoming,
                    "Не хочу гадать со временем. Выберите кнопку или напишите время в формате 17:30.",
                    action,
                    timeRows()
            );
            case "CHANGE_DATE" -> changeWorkInProgress(
                    incoming,
                    "Не смог уверенно понять дату. Выберите день кнопкой или напишите: «завтра», «в пятницу», «30.06».",
                    action,
                    dateRows()
            );
            default -> null;
        };
    }

    private Optional<TableReservationChangeCommand> changePartySizeCommand(
            IncomingMessage incoming,
            String normalized,
            TableReservationOrder current
    ) {
        Optional<Integer> partySize = partySizeFromText(incoming.text(), normalized);
        return partySize.map(size -> new TableReservationChangeCommand(
                "AERIS",
                null,
                null,
                current.seatingPreference(),
                current.requestedStartAt(),
                current.requestedEndAt(),
                size,
                appendGuestComment(current, "Количество гостей изменено: " + size)
        ));
    }

    private Optional<TableReservationChangeCommand> changeTimeCommand(
            IncomingMessage incoming,
            String normalized,
            TableReservationOrder current
    ) {
        Optional<LocalTime> time = timeFromText(incoming.text(), normalized);
        if (time.isEmpty() || current.requestedStartAt() == null) {
            return Optional.empty();
        }
        LocalDate date = current.requestedStartAt().atZone(BookingTimeProvider.VENUE_ZONE).toLocalDate();
        Instant startAt = date.atTime(time.get()).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        return Optional.of(new TableReservationChangeCommand(
                "AERIS",
                null,
                null,
                current.seatingPreference(),
                startAt,
                startAt.plusSeconds(2 * 60 * 60L),
                current.partySize(),
                appendGuestComment(current, "Время изменено: " + time.get())
        ));
    }

    private Optional<TableReservationChangeCommand> changeDateCommand(
            IncomingMessage incoming,
            String normalized,
            TableReservationOrder current
    ) {
        Optional<LocalDate> date = dateFromText(incoming.text(), normalized);
        if (date.isEmpty() || current.requestedStartAt() == null) {
            return Optional.empty();
        }
        LocalTime time = current.requestedStartAt().atZone(BookingTimeProvider.VENUE_ZONE).toLocalTime();
        Instant startAt = date.get().atTime(time).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        return Optional.of(new TableReservationChangeCommand(
                "AERIS",
                null,
                null,
                current.seatingPreference(),
                startAt,
                startAt.plusSeconds(2 * 60 * 60L),
                current.partySize(),
                appendGuestComment(current, "Дата изменена: " + date.get())
        ));
    }

    private Optional<TableReservationChangeCommand> changeTableCommand(
            IncomingMessage incoming,
            String normalized,
            TableReservationOrder current
    ) {
        String tableCode = tableCodeFromText(normalized).orElse(null);
        String preferredZone = preferredZoneFromText(normalized).orElse(null);
        boolean auto = containsAny(normalized, "подбери сам", "выбери сам", "сам выбери", "сам подбери", "любой", "где удобно");
        if (tableCode == null && preferredZone == null && !auto) {
            return Optional.empty();
        }
        return Optional.of(new TableReservationChangeCommand(
                "AERIS",
                tableCode,
                preferredZone,
                seatingPreferenceFromText(normalized).orElse(current.seatingPreference()),
                current.requestedStartAt(),
                current.requestedEndAt(),
                current.partySize(),
                appendGuestComment(current, "Стол/зона изменены: " + blank(incoming.text(), normalized))
        ));
    }

    private Optional<Integer> partySizeFromText(String raw, String normalized) {
        UnderstoodInput understood = understandingService.understand(raw, BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE);
        Optional<Integer> fromSlot = Optional.ofNullable(understood.slots().get("partySize"))
                .map(SlotValue::value)
                .flatMap(this::parsePositiveInt);
        if (fromSlot.isPresent()) {
            return fromSlot;
        }
        if (containsAny(normalized, "одного", "один", "одна", "одному", "соло", "только я")) {
            return Optional.of(1);
        }
        if (containsAny(normalized, "двоих", "двоем", "двух", "двое")) {
            return Optional.of(2);
        }
        if (containsAny(normalized, "троих", "трое", "трех", "трёх")) {
            return Optional.of(3);
        }
        if (containsAny(normalized, "четверых", "четверо", "четырех", "четырёх")) {
            return Optional.of(4);
        }
        if (containsAny(normalized, "пятерых", "пятеро", "пяти", "пять")) {
            return Optional.of(5);
        }
        Matcher matcher = Pattern.compile("^(?:на\\s*)?(\\d{1,2})(?:\\s*(?:гостей|гостя|человек|персон|чел))?$").matcher(normalized);
        return matcher.matches() ? parsePositiveInt(matcher.group(1)) : Optional.empty();
    }

    private Optional<LocalTime> timeFromText(String raw, String normalized) {
        UnderstoodInput understood = understandingService.understand(raw, BotState.TABLE_BOOKING_COLLECT_TIME);
        Optional<LocalTime> fromSlot = Optional.ofNullable(understood.slots().get("time"))
                .map(SlotValue::value)
                .flatMap(this::parseTime);
        if (fromSlot.isPresent()) {
            return fromSlot;
        }
        Matcher explicit = EXPLICIT_TIME.matcher(normalized);
        if (explicit.find()) {
            return Optional.of(LocalTime.of(Integer.parseInt(explicit.group(1)), Integer.parseInt(explicit.group(2))));
        }
        Matcher hour = SHORT_HOUR.matcher(normalized);
        if (hour.matches()) {
            int parsed = Integer.parseInt(hour.group(1));
            return Optional.of(LocalTime.of(parsed >= 1 && parsed <= 11 ? parsed + 12 : parsed, 0));
        }
        return Optional.empty();
    }

    private Optional<LocalDate> dateFromText(String raw, String normalized) {
        UnderstoodInput understood = understandingService.understand(raw, BotState.TABLE_BOOKING_COLLECT_DATE);
        Optional<LocalDate> fromSlot = Optional.ofNullable(understood.slots().get("date"))
                .map(SlotValue::value)
                .flatMap(value -> parseDate(value, normalized));
        return fromSlot.or(() -> parseDate(raw, normalized));
    }

    private Optional<LocalDate> parseDate(String raw, String normalized) {
        String text = normalize(raw == null || raw.isBlank() ? normalized : raw);
        LocalDate today = timeProvider.today();
        if (text.contains("послезавтра")) {
            return Optional.of(today.plusDays(2));
        }
        if (text.contains("завтра")) {
            return Optional.of(today.plusDays(1));
        }
        if (text.contains("сегодня")) {
            return Optional.of(today);
        }
        Optional<java.time.DayOfWeek> day = weekdayFromText(text);
        if (day.isPresent()) {
            return Optional.of(today.with(TemporalAdjusters.nextOrSame(day.get())));
        }
        return GuestDateText.dayMonth(text, today);
    }

    private Optional<java.time.DayOfWeek> weekdayFromText(String text) {
        if (containsAny(text, "понедельник", "понедельника")) {
            return Optional.of(java.time.DayOfWeek.MONDAY);
        }
        if (containsAny(text, "вторник", "вторника")) {
            return Optional.of(java.time.DayOfWeek.TUESDAY);
        }
        if (containsAny(text, "среду", "среда", "среды")) {
            return Optional.of(java.time.DayOfWeek.WEDNESDAY);
        }
        if (containsAny(text, "четверг", "четверга")) {
            return Optional.of(java.time.DayOfWeek.THURSDAY);
        }
        if (containsAny(text, "пятницу", "пятница", "пятницы")) {
            return Optional.of(java.time.DayOfWeek.FRIDAY);
        }
        if (containsAny(text, "субботу", "суббота", "субботы")) {
            return Optional.of(java.time.DayOfWeek.SATURDAY);
        }
        if (containsAny(text, "воскресенье", "воскресенья")) {
            return Optional.of(java.time.DayOfWeek.SUNDAY);
        }
        return Optional.empty();
    }

    private Optional<String> tableCodeFromText(String text) {
        Matcher reverse = Pattern.compile("(?:^|\\s)(1\\d|[1-9])\\s*стол(?:ик)?(?:\\s|$)").matcher(text);
        if (reverse.find()) {
            return Optional.of(reverse.group(1));
        }
        Matcher matcher = Pattern.compile("^(?:стол(?:ик)?\\s*)?(1\\d|[1-9])$").matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Optional<String> preferredZoneFromText(String text) {
        if (containsAny(text, "vip", "вип")) {
            return Optional.of("VIP_ZONE");
        }
        if (containsAny(text, "wine", "винн")) {
            return Optional.of("WINE_ROOM");
        }
        if (text.contains("бар")) {
            return Optional.of("BAR");
        }
        if (containsAny(text, "окн", "у окна", "возле окна")) {
            return Optional.of("WINDOW");
        }
        if (containsAny(text, "угл", "углов")) {
            return Optional.of("CORNER");
        }
        if (containsAny(text, "центр", "блест", "в центре")) {
            return Optional.of("CENTER_STAGE");
        }
        if (containsAny(text, "диван", "лаунж", "lounge", "11", "12")) {
            return Optional.of("SOFT_LOUNGE");
        }
        return Optional.empty();
    }

    private Optional<String> seatingPreferenceFromText(String text) {
        if (containsAny(text, "подбери сам", "выбери сам", "сам выбери", "сам подбери", "любой", "где удобно")) {
            return Optional.empty();
        }
        if (preferredZoneFromText(text).isPresent() || containsAny(text, "тих", "уют", "не проход")) {
            return Optional.of(text);
        }
        return Optional.empty();
    }

    private Optional<Integer> parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 20 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private Optional<LocalTime> parseTime(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Matcher explicit = EXPLICIT_TIME.matcher(value.trim());
        if (explicit.find()) {
            return Optional.of(LocalTime.of(Integer.parseInt(explicit.group(1)), Integer.parseInt(explicit.group(2))));
        }
        return Optional.empty();
    }

    private String appendGuestComment(TableReservationOrder current, String addition) {
        String existing = current.guestComment() == null ? "" : current.guestComment().trim();
        if (existing.isBlank()) {
            return addition;
        }
        if (existing.contains(addition)) {
            return existing;
        }
        return existing + " | " + addition;
    }

    @Override
    public boolean owns(BotState state) {
        BotState canonical = state == null ? BotState.UNKNOWN : state.canonical();
        return canonical == BotState.TABLE_BOOKING_CHANGE_REQUESTED;
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    private OutgoingMessage sendChangeCancelRequest(IncomingMessage incoming, BotState previousState, String text) {
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                "Передал запрос команде. Бронь не меняю и не отменяю автоматически: менеджер проверит активную заявку и подтвердит следующий шаг.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                adminAlert(incoming, previousState, text),
                List.of("CHANGE_CANCEL", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of(
                "scenario", id(),
                "changeCancelRequest", text == null ? "" : text.trim()
        ));
    }

    private AdminAlert adminAlert(IncomingMessage incoming, BotState previousState, String text) {
        if (adminChatId == null || adminChatId.isBlank()) {
            return AdminAlert.none();
        }
        String body = """
                <b>Astor Butler / change or cancel</b>
                Гость просит изменить или отменить бронь

                <b>%s</b>
                chat %s / user %s%s

                <b>Сообщение гостя</b>
                <blockquote>%s</blockquote>

                <b>Контекст</b>
                Previous state: %s
                Scenario: CHANGE_CANCEL

                <b>Действие</b>
                Найдите активную бронь/заявку. Не отменяйте без явного подтверждения гостя и команды.

                <b>Техника</b>
                Channel: %s
                Correlation: %s
                """.formatted(
                html(displayName(incoming)),
                html(text(incoming.chatId())),
                html(text(incoming.telegramUserId())),
                incoming.username() == null || incoming.username().isBlank() ? "" : " / @" + html(incoming.username()),
                html(blankAsEmptyLabel(text)),
                html(text(previousState)),
                html(text(incoming.channel())),
                html(blankAsEmptyLabel(incoming.correlationId()))
        );
        return new AdminAlert(true, adminChatId, body);
    }

    private boolean isChangeCancelIntent(String text) {
        return containsAny(
                text,
                "отменить брон",
                "отмена брон",
                "отменить стол",
                "отменить заявку",
                "перенести брон",
                "изменить брон",
                "поменять брон",
                "изменить время",
                "поменять время",
                "перенести время",
                "не придем",
                "не придём",
                "не сможем",
                "опоздаем",
                "задержимся"
        );
    }

    private boolean isCancelIntent(String text) {
        return containsAny(text, "отмен", "не придем", "не придём", "не сможем");
    }

    private Long referencedId(String text) {
        java.util.regex.Matcher hash = java.util.regex.Pattern.compile("#\\s*(\\d{1,8})").matcher(text);
        if (hash.find()) {
            return Long.parseLong(hash.group(1));
        }
        java.util.regex.Matcher order = java.util.regex.Pattern
                .compile("(?:заказ|заявк[ауи]|бронь|брон[ьи])\\s*(?:номер|№|#)?\\s*(\\d{1,8})")
                .matcher(text);
        if (order.find()) {
            return Long.parseLong(order.group(1));
        }
        return null;
    }

    private boolean containsReference(String text) {
        return text.matches(".*\\b\\d{1,2}[:.]\\d{2}\\b.*")
                || text.matches(".*\\b\\d{1,2}[./-]\\d{1,2}.*")
                || text.matches(".*\\b\\d{3,}\\b.*")
                || containsAny(text, "сегодня", "завтра", "послезавтра", "вечером", "обед", "ужин");
    }

    private boolean containsAny(String text, String... variants) {
        for (String variant : variants) {
            if (text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private String displayName(IncomingMessage incoming) {
        String firstName = normalizeDisplay(incoming.firstName());
        String lastName = normalizeDisplay(incoming.lastName());
        String username = normalizeDisplay(incoming.username());
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (!username.isBlank()) {
            return "@" + username;
        }
        return "unknown";
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplay(String text) {
        return text == null ? "" : text.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String blankAsEmptyLabel(String value) {
        String normalized = normalizeDisplay(value);
        return normalized.isBlank() ? "(empty)" : normalized;
    }

    private String blank(String value, String fallback) {
        String normalized = normalizeDisplay(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String html(String value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
