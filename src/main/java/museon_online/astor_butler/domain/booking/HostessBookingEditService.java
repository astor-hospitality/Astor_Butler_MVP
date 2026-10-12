package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.BookingHostessStateStorage.EditSession;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «✏️ Изменить» on the hostess card (#150): the hostess fixes a booking in two or three presses without leaving the
 * staff chat. The button opens a panel under the card with «Стол» (tables free for that slot), «Время» (slots of
 * that evening) and «Гостей» (−1 / +1 / a number); every press is applied at once through
 * {@link TableReservationService#changeByStaff}, with the same checks as a guest's change, and the panel shows the
 * result or why it was refused. The panel lives in Redis for a short while after the last press; a later press tells
 * her to open it again. Only the hostess chat may press, like the other buttons of the card; the feature is off by
 * default ({@code astor.booking.staff-edit.enabled}).
 */
@Service
@Slf4j
public class HostessBookingEditService {

    public static final String CALLBACK_PREFIX = "table_booking:edit:";
    static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Yekaterinburg");
    static final String DEFAULT_VENUE_CODE = "AERIS";

    private static final Pattern CALLBACK = Pattern.compile(
            "^table_booking:edit:(\\d{1,18})(?::(menu|table|time|party|cancel)(?::([A-Za-z0-9_.+-]{1,16}))?)?$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(VENUE_ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(VENUE_ZONE);
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Duration DEFAULT_VISIT = Duration.ofHours(2);
    private static final Duration SLOT_WINDOW_BEFORE = Duration.ofHours(2);
    private static final int TABLES_PER_ROW = 3;
    private static final int SLOTS_PER_ROW = 4;
    private static final int NUMBERS_PER_ROW = 4;

    private final BookingStaffEditProperties properties;
    private final TableReservationService reservations;
    private final BookingHostessStateStorage storage;
    private final VenueOpeningHours openingHours;
    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final TelegramSystemNotifier systemNotifier;
    private final Clock clock;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled = true;

    @Autowired
    public HostessBookingEditService(BookingStaffEditProperties properties,
                                     TableReservationService reservations,
                                     BookingHostessStateStorage storage,
                                     VenueOpeningHours openingHours,
                                     ObjectProvider<TelegramBot> telegramBotProvider,
                                     TelegramSystemNotifier systemNotifier) {
        this(properties, reservations, storage, openingHours, telegramBotProvider, systemNotifier, Clock.systemUTC());
    }

    HostessBookingEditService(BookingStaffEditProperties properties,
                              TableReservationService reservations,
                              BookingHostessStateStorage storage,
                              VenueOpeningHours openingHours,
                              ObjectProvider<TelegramBot> telegramBotProvider,
                              TelegramSystemNotifier systemNotifier,
                              Clock clock) {
        this.properties = properties;
        this.reservations = reservations;
        this.storage = storage;
        this.openingHours = openingHours;
        this.telegramBotProvider = telegramBotProvider;
        this.systemNotifier = systemNotifier;
        this.clock = clock;
    }

    public record CallbackResult(boolean handled, String answerText) {
        static final CallbackResult NOT_HANDLED = new CallbackResult(false, "");

        static CallbackResult handled(String answerText) {
            return new CallbackResult(true, answerText);
        }
    }

    /** The «✏️ Изменить» button of a reservation, as the card builder puts it on the card. */
    public static String callbackData(Long orderId) {
        return CALLBACK_PREFIX + orderId;
    }

    /** How a staff member is named in the log, the panel and the system chat: «@dasha», «Даша (id 42)» or «id 42». */
    public static String staffRef(String username, String firstName, Long userId) {
        if (username != null && !username.isBlank()) {
            return "@" + username.trim();
        }
        if (firstName != null && !firstName.isBlank()) {
            return firstName.trim() + (userId == null ? "" : " (id " + userId + ")");
        }
        return userId == null ? "неизвестно" : "id " + userId;
    }

    /**
     * @param messageId the message the button is on: the card for «✏️ Изменить», the panel for every step
     * @param staffRef  who pressed, see {@link #staffRef}
     */
    public CallbackResult handleCallback(String callbackData, Long chatId, Integer messageId, String staffRef) {
        if (callbackData == null) {
            return CallbackResult.NOT_HANDLED;
        }
        Matcher matcher = CALLBACK.matcher(callbackData.trim());
        if (!matcher.matches() || !isHostessChat(chatId)) {
            return CallbackResult.NOT_HANDLED;
        }
        long orderId = Long.parseLong(matcher.group(1));
        String step = matcher.group(2) == null ? "open" : matcher.group(2);
        String value = matcher.group(3);
        if (!properties.isEnabled()) {
            return CallbackResult.handled("Правка брони выключена (ASTOR_BOOKING_STAFF_EDIT_ENABLED)");
        }

        TableReservationOrder order;
        try {
            order = reservations.getReservation(orderId);
        } catch (ApiException e) {
            return CallbackResult.handled("Бронь #" + orderId + " не найдена");
        }
        if ("cancel".equals(step)) {
            return close(order, chatId, messageId);
        }
        if (!editable(order)) {
            if (!"open".equals(step)) {
                storage.endEditSession(order.id());
                stripButtons(chatId.toString(), messageId);
            }
            return CallbackResult.handled("Бронь #" + orderId + " " + closedStatusWord(order.status()) + " — менять нечего");
        }
        String who = staffRef == null || staffRef.isBlank() ? "неизвестно" : staffRef;
        return switch (step) {
            case "open" -> open(order, chatId, messageId, who);
            case "menu" -> withSession(order, chatId, messageId, session -> showMenu(order, session, null));
            case "table" -> withSession(order, chatId, messageId, session -> value == null
                    ? showTables(order, session, null)
                    : applyTable(order, session, value, who));
            case "time" -> withSession(order, chatId, messageId, session -> value == null
                    ? showSlots(order, session, null)
                    : applyTime(order, session, value, who));
            case "party" -> withSession(order, chatId, messageId, session -> value == null
                    ? showParty(order, session, null)
                    : applyParty(order, session, value, who));
            default -> CallbackResult.NOT_HANDLED;
        };
    }

    // ---- steps ----

    private CallbackResult open(TableReservationOrder order, Long chatId, Integer cardMessageId, String staffRef) {
        storage.editSession(order.id()).ifPresent(old -> stripButtons(old.chatId(), old.messageId()));
        EditSession fresh = new EditSession(chatId.toString(), null, staffRef, clock.instant(), "");
        Message panel = send(chatId.toString(), panelText(order, fresh, menuPrompt()), menuKeyboard(order.id()), cardMessageId);
        if (panel == null) {
            return CallbackResult.handled("Не удалось открыть правку брони #" + order.id() + ": Telegram недоступен, попробуйте ещё раз");
        }
        storage.startEditSession(order.id(),
                new EditSession(chatId.toString(), panel.getMessageId(), staffRef, fresh.startedAt(), ""),
                properties.sessionTtl());
        return CallbackResult.handled("Правка брони #" + order.id() + " открыта");
    }

    /** A step is accepted only from the live panel; a stale or expired one loses its buttons and says so. */
    private CallbackResult withSession(TableReservationOrder order, Long chatId, Integer messageId,
                                       Function<EditSession, CallbackResult> step) {
        Optional<EditSession> session = storage.editSession(order.id())
                .filter(live -> chatId.toString().equals(live.chatId()) && Objects.equals(live.messageId(), messageId));
        if (session.isEmpty()) {
            stripButtons(chatId.toString(), messageId);
            return CallbackResult.handled("Правка брони #" + order.id() + " истекла — нажмите «✏️ Изменить» на карточке ещё раз");
        }
        storage.touchEditSession(order.id(), properties.sessionTtl());
        return step.apply(session.get());
    }

    private CallbackResult showMenu(TableReservationOrder order, EditSession session, String note) {
        editPanel(session, panelText(order, session, note == null ? menuPrompt() : note + "\n\n" + menuPrompt()), menuKeyboard(order.id()));
        return CallbackResult.handled("Бронь #" + order.id() + ": что меняем?");
    }

    private CallbackResult showTables(TableReservationOrder order, EditSession session, String error) {
        List<VenueTable> free = reservations.tablesFreeFor(order);
        StringBuilder note = new StringBuilder();
        if (error != null) {
            note.append("⚠️ ").append(escape(error)).append("\n\n");
        }
        if (free.isEmpty()) {
            note.append("Свободных столов на %s для %s гостей нет — измените время или число гостей."
                    .formatted(formatTimeRange(order), order.partySize()));
        } else {
            note.append("<b>Стол</b> на %s для %s гостей — нажмите нужный (• сейчас):"
                    .formatted(formatTimeRange(order), order.partySize()));
        }
        editPanel(session, panelText(order, session, note.toString()), tablesKeyboard(order, free));
        return CallbackResult.handled(error != null ? error : "Бронь #" + order.id() + ": выберите стол");
    }

    private CallbackResult applyTable(TableReservationOrder order, EditSession session, String tableCode, String staffRef) {
        if (order.tableCode() != null && order.tableCode().equalsIgnoreCase(tableCode)) {
            return showMenu(order, session, "Это и есть текущий стол — ничего не менял.");
        }
        TableReservationChangeCommand command = new TableReservationChangeCommand(
                DEFAULT_VENUE_CODE, tableCode, null, null, null, null, null, null);
        try {
            TableReservationOrder changed = reservations.changeByStaff(order.id(), command, staffRef);
            String summary = "стол " + order.tableCode() + " → " + changed.tableCode();
            return applied(changed, session, summary, "Гостю не сообщаем.", staffRef);
        } catch (ApiException e) {
            return showTables(order, session, humanError(e, tableCode));
        }
    }

    private CallbackResult showSlots(TableReservationOrder order, EditSession session, String error) {
        List<Instant> slots = slots(order);
        StringBuilder note = new StringBuilder();
        if (error != null) {
            note.append("⚠️ ").append(escape(error)).append("\n\n");
        }
        if (slots.isEmpty()) {
            note.append("На %s свободных слотов больше нет: время прошло или заведение закрыто."
                    .formatted(formatDate(order.requestedStartAt())));
        } else {
            note.append("<b>Время</b> на %s, визит %s, стол тот же — нажмите новое начало (• сейчас):"
                    .formatted(formatDate(order.requestedStartAt()), humanDuration(visitLength(order))));
        }
        editPanel(session, panelText(order, session, note.toString()), slotsKeyboard(order, slots));
        return CallbackResult.handled(error != null ? error : "Бронь #" + order.id() + ": выберите время");
    }

    private CallbackResult applyTime(TableReservationOrder order, EditSession session, String value, String staffRef) {
        Instant start;
        try {
            start = LocalDateTime.parse(value, SLOT).atZone(VENUE_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return showSlots(order, session, "Не понял время — выберите кнопкой");
        }
        if (!start.isAfter(clock.instant())) {
            return showSlots(order, session, "Это время уже прошло");
        }
        Instant end = start.plus(visitLength(order));
        if (start.equals(order.requestedStartAt()) && end.equals(order.requestedEndAt())) {
            return showMenu(order, session, "Это и есть текущее время — ничего не менял.");
        }
        TableReservationChangeCommand command = new TableReservationChangeCommand(
                DEFAULT_VENUE_CODE, order.tableCode(), null, null, start, end, null, null);
        try {
            TableReservationOrder changed = reservations.changeByStaff(order.id(), command, staffRef);
            String summary = "время " + describeStart(order) + " → " + describeStart(changed);
            return applied(changed, session, summary, "Гостю отправлено сообщение о переносе.", staffRef);
        } catch (ApiException e) {
            return showSlots(order, session, humanError(e, order.tableCode()));
        }
    }

    private CallbackResult showParty(TableReservationOrder order, EditSession session, String error) {
        StringBuilder note = new StringBuilder();
        if (error != null) {
            note.append("⚠️ ").append(escape(error)).append("\n\n");
        }
        note.append("<b>Гостей</b> сейчас: %s%s — нажмите −1 / +1 или число:"
                .formatted(order.partySize(), capacityHint(order)));
        editPanel(session, panelText(order, session, note.toString()), partyKeyboard(order));
        return CallbackResult.handled(error != null ? error : "Бронь #" + order.id() + ": сколько гостей?");
    }

    private CallbackResult applyParty(TableReservationOrder order, EditSession session, String value, String staffRef) {
        int current = order.partySize() == null ? 1 : order.partySize();
        int target;
        switch (value) {
            case "inc" -> target = current + 1;
            case "dec" -> target = current - 1;
            default -> {
                try {
                    target = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return showParty(order, session, "Не понял число — нажмите кнопку");
                }
            }
        }
        if (target < 1) {
            return showParty(order, session, "Меньше одного гостя не бывает");
        }
        if (target > properties.getMaxParty()) {
            return showParty(order, session, "Больше " + properties.getMaxParty() + " гостей — оформите через менеджера");
        }
        if (target == current) {
            return showMenu(order, session, "Уже " + current + " " + guestsWord(current) + " — ничего не менял.");
        }
        TableReservationChangeCommand command = new TableReservationChangeCommand(
                DEFAULT_VENUE_CODE, order.tableCode(), null, null, null, null, target, null);
        try {
            TableReservationOrder changed = reservations.changeByStaff(order.id(), command, staffRef);
            String summary = "гостей " + current + " → " + changed.partySize();
            return applied(changed, session, summary, "Гостю не сообщаем.", staffRef);
        } catch (ApiException e) {
            return showParty(order, session, humanError(e, order.tableCode()));
        }
    }

    /** A change went through: a line in the panel's journal, one in the system chat, the menu again with the new data. */
    private CallbackResult applied(TableReservationOrder changed, EditSession session, String summary, String guestNote, String staffRef) {
        String line = TIME.format(clock.instant()) + " " + staffRef + " — " + summary;
        storage.appendEditLog(changed.id(), line, properties.sessionTtl());
        try {
            systemNotifier.sendBookingStaffChange(staffRef, changed.id(), summary);
        } catch (RuntimeException e) {
            log.debug("Booking staff change was not traced to the system chat: orderId={}, reason={}", changed.id(), e.toString());
        }
        EditSession updated = new EditSession(session.chatId(), session.messageId(), session.startedBy(), session.startedAt(),
                session.log() == null || session.log().isBlank() ? line : session.log() + "\n" + line);
        editPanel(session, panelText(changed, updated, "✅ Готово: " + escape(summary) + ". " + guestNote + "\n\n" + menuPrompt()),
                menuKeyboard(changed.id()));
        return CallbackResult.handled("Бронь #" + changed.id() + ": " + summary);
    }

    private CallbackResult close(TableReservationOrder order, Long chatId, Integer messageId) {
        Optional<EditSession> session = storage.editSession(order.id());
        storage.endEditSession(order.id());
        if (session.isPresent() && chatId.toString().equals(session.get().chatId())
                && Objects.equals(session.get().messageId(), messageId)) {
            editPanel(session.get(), panelText(order, session.get(), "Правка закрыта."), null);
        } else {
            stripButtons(chatId.toString(), messageId);
        }
        return CallbackResult.handled("Правка брони #" + order.id() + " закрыта");
    }

    // ---- texts and keyboards ----

    String panelText(TableReservationOrder order, EditSession session, String note) {
        StringBuilder text = new StringBuilder();
        text.append("✏️ <b>Правка брони #").append(order.id()).append("</b> — ")
                .append(escape(blank(order.guestName(), "гость из Telegram"))).append('\n');
        text.append(escape(TableReservationNotificationService.tableName(order)))
                .append(" · ").append(formatDate(order.requestedStartAt())).append(", ").append(formatTimeRange(order))
                .append(" · гостей: ").append(order.partySize()).append('\n');
        text.append("Статус: ").append(humanStatus(order.status())).append('\n');
        if (hasExternalId(order)) {
            text.append("\n⚠️ <b>Saby:</b> бронь уже в Presto — после правки поменяйте там время и число гостей вручную; "
                    + "стол в Presto и так ведёте вы.\n");
        }
        List<String> lines = session == null ? List.of() : session.logLines();
        if (!lines.isEmpty()) {
            text.append("\n<b>Изменения</b>\n");
            for (String line : lines) {
                text.append("• ").append(escape(line)).append('\n');
            }
        }
        if (note != null && !note.isBlank()) {
            text.append('\n').append(note).append('\n');
        }
        return text.toString();
    }

    private String menuPrompt() {
        return "Что меняем? Без нажатий правка закроется через " + properties.sessionTtl().toMinutes() + " мин.";
    }

    InlineKeyboardMarkup menuKeyboard(Long orderId) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                List.of(
                        button("🪑 Стол", step(orderId, "table", null)),
                        button("🕒 Время", step(orderId, "time", null)),
                        button("👥 Гостей", step(orderId, "party", null))
                ),
                List.of(button("Закрыть", step(orderId, "cancel", null)))
        )).build();
    }

    InlineKeyboardMarkup tablesKeyboard(TableReservationOrder order, List<VenueTable> free) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (VenueTable table : free) {
            boolean current = order.tableCode() != null && order.tableCode().equalsIgnoreCase(table.tableCode());
            row.add(button((current ? "• " : "") + shortTableLabel(table), step(order.id(), "table", table.tableCode())));
            if (row.size() == TABLES_PER_ROW) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button("← Назад", step(order.id(), "menu", null))));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    InlineKeyboardMarkup slotsKeyboard(TableReservationOrder order, List<Instant> slots) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (Instant slot : slots) {
            boolean current = slot.equals(order.requestedStartAt());
            String data = step(order.id(), "time", SLOT.format(slot.atZone(VENUE_ZONE)));
            row.add(button((current ? "• " : "") + TIME.format(slot), data));
            if (row.size() == SLOTS_PER_ROW) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button("← Назад", step(order.id(), "menu", null))));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    InlineKeyboardMarkup partyKeyboard(TableReservationOrder order) {
        int current = order.partySize() == null ? 1 : order.partySize();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                button("−1", step(order.id(), "party", "dec")),
                button("👥 " + current, step(order.id(), "party", null)),
                button("+1", step(order.id(), "party", "inc"))
        ));
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int n = 1; n <= properties.getMaxParty(); n++) {
            row.add(button((n == current ? "• " : "") + n, step(order.id(), "party", String.valueOf(n))));
            if (row.size() == NUMBERS_PER_ROW) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button("← Назад", step(order.id(), "menu", null))));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * The starts the hostess may move the booking to: the evening the booking belongs to, from the venue's opening
     * through its late hours after midnight, at the configured step, never in the past. When there are more than fit,
     * the window starts shortly before the current time.
     */
    List<Instant> slots(TableReservationOrder order) {
        if (order.requestedStartAt() == null) {
            return List.of();
        }
        ZonedDateTime current = order.requestedStartAt().atZone(VENUE_ZONE);
        LocalDate date = current.toLocalDate();
        Duration step = properties.slotStep();
        List<Instant> candidates = new ArrayList<>();
        for (LocalTime time = LocalTime.MIDNIGHT; ; time = time.plus(step)) {
            if (openingHours.isOpen(date, time) && !openingHours.isLateHourOf(date.minusDays(1), time)) {
                candidates.add(date.atTime(time).atZone(VENUE_ZONE).toInstant());
            } else if (openingHours.isLateHourOf(date, time)) {
                candidates.add(date.plusDays(1).atTime(time).atZone(VENUE_ZONE).toInstant());
            }
            LocalTime next = time.plus(step);
            if (!next.isAfter(time)) {
                break;
            }
        }
        Instant now = clock.instant();
        List<Instant> future = candidates.stream().sorted().filter(slot -> slot.isAfter(now)).toList();
        int max = Math.max(1, properties.getMaxSlots());
        if (future.size() <= max) {
            return future;
        }
        Instant from = current.toInstant().minus(SLOT_WINDOW_BEFORE);
        int first = 0;
        while (first < future.size() && future.get(first).isBefore(from)) {
            first++;
        }
        first = Math.min(first, future.size() - max);
        return future.subList(first, first + max);
    }

    private static String step(Long orderId, String step, String value) {
        return CALLBACK_PREFIX + orderId + ":" + step + (value == null ? "" : ":" + value);
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    /** What the guest or the hostess is told when a change is refused, by the reason the service gives. */
    static String humanError(ApiException e, String tableCode) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        Object named = e.details() == null ? null : e.details().get("tableCode");
        String code = named == null ? tableCode : String.valueOf(named);
        String table = code == null || code.isBlank() ? "Стол" : "Стол " + code;
        if (message.startsWith("Table capacity is lower")) {
            return table + " вмещает меньше гостей — выберите другой стол или число гостей";
        }
        if (message.startsWith("Table already has an active hold")) {
            return table + " занят на это время другой бронью";
        }
        if (message.startsWith("Table is busy in the restaurant")) {
            return table + " занят на это время в Presto";
        }
        if (message.startsWith("Table is not bookable")) {
            return table + " сейчас не бронируется";
        }
        if (message.startsWith("Requested table was not found")) {
            return table + " не найден";
        }
        if (message.startsWith("Only active table reservations")) {
            return "Бронь уже не активна";
        }
        if (message.startsWith("No available table")) {
            return "Свободного стола на это время нет";
        }
        if (message.startsWith("requestedEndAt must be after") || message.startsWith("requestedStartAt and requestedEndAt")) {
            return "Время указано неверно";
        }
        if (message.startsWith("partySize must be positive")) {
            return "Число гостей указано неверно";
        }
        return message.isBlank() ? "Не получилось применить изменение" : message;
    }

    // ---- telegram ----

    private Message send(String chatId, String text, InlineKeyboardMarkup keyboard, Integer replyToMessageId) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null || chatId.isBlank()) {
            log.debug("Booking edit panel skipped: botEnabled={}, notificationsEnabled={}", telegramEnabled, notificationsEnabled);
            return null;
        }
        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                return null;
            }
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard);
            if (replyToMessageId != null) {
                builder.replyToMessageId(replyToMessageId).allowSendingWithoutReply(true);
            }
            return telegramBot.execute(builder.build());
        } catch (Exception e) {
            log.warn("Booking edit panel was not sent: chatId={}, reason={}", chatId, e.getMessage());
            return null;
        }
    }

    private void editPanel(EditSession session, String text, InlineKeyboardMarkup keyboard) {
        if (!telegramEnabled || !notificationsEnabled || session == null || session.messageId() == null) {
            return;
        }
        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                return;
            }
            telegramBot.execute(EditMessageText.builder()
                    .chatId(session.chatId())
                    .messageId(session.messageId())
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
        } catch (Exception e) {
            log.debug("Booking edit panel was not updated: chatId={}, messageId={}, reason={}",
                    session.chatId(), session.messageId(), e.getMessage());
        }
    }

    /** A panel that is no longer live loses its buttons, so a stale press cannot be mistaken for a working one. */
    private void stripButtons(String chatId, Integer messageId) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null || messageId == null) {
            return;
        }
        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                return;
            }
            telegramBot.execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .replyMarkup(null)
                    .build());
        } catch (Exception e) {
            log.debug("Booking edit panel buttons were not removed: chatId={}, messageId={}, reason={}", chatId, messageId, e.getMessage());
        }
    }

    // ---- helpers ----

    private static boolean editable(TableReservationOrder order) {
        return order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                || order.status() == TableReservationStatus.CONFIRMED;
    }

    private static boolean hasExternalId(TableReservationOrder order) {
        return order.sbisExternalId() != null && !order.sbisExternalId().isBlank();
    }

    private static Duration visitLength(TableReservationOrder order) {
        if (order.requestedStartAt() == null || order.requestedEndAt() == null
                || !order.requestedEndAt().isAfter(order.requestedStartAt())) {
            return DEFAULT_VISIT;
        }
        return Duration.between(order.requestedStartAt(), order.requestedEndAt());
    }

    private static String describeStart(TableReservationOrder order) {
        return order.requestedStartAt() == null ? "не указано" : TIME.format(order.requestedStartAt());
    }

    private String capacityHint(TableReservationOrder order) {
        if (order.tableCode() == null) {
            return "";
        }
        return reservations.listTables(DEFAULT_VENUE_CODE).stream()
                .filter(table -> order.tableCode().equalsIgnoreCase(table.tableCode()))
                .findFirst()
                .map(table -> " (" + shortTableLabel(table) + ", до " + table.capacityMax() + " мест)")
                .orElse("");
    }

    private static String shortTableLabel(VenueTable table) {
        if (table.displayName() == null || table.displayName().isBlank()) {
            return "Стол " + table.tableCode();
        }
        return TableReservationNotificationService.humanTableDisplayName(table.displayName());
    }

    private static String shortTableLabel(TableReservationOrder order) {
        if (order.tableCode() == null) {
            return "не выбран";
        }
        if (order.tableDisplayName() == null || order.tableDisplayName().isBlank()) {
            return "Стол " + order.tableCode();
        }
        return TableReservationNotificationService.humanTableDisplayName(order.tableDisplayName());
    }

    static String humanDuration(Duration duration) {
        long minutes = duration.toMinutes();
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + " мин";
        }
        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    private static String guestsWord(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "гость";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "гостя";
        }
        return "гостей";
    }

    private static String humanStatus(TableReservationStatus status) {
        if (status == null) {
            return "неизвестен";
        }
        return switch (status) {
            case AWAITING_MANAGER_CONFIRMATION -> "ожидает решения хостес";
            case CONFIRMED -> "подтверждена";
            case REJECTED -> "отклонена";
            case CANCELLED -> "отменена";
            case EXPIRED -> "истекла";
            default -> status.name();
        };
    }

    private static String closedStatusWord(TableReservationStatus status) {
        if (status == null) {
            return "в неизвестном статусе";
        }
        return switch (status) {
            case REJECTED -> "уже отклонена";
            case CANCELLED -> "уже отменена";
            case EXPIRED -> "истекла";
            default -> "в статусе " + status.name();
        };
    }

    private static String formatDate(Instant instant) {
        return instant == null ? "не указана" : DATE.format(instant);
    }

    private static String formatTimeRange(TableReservationOrder order) {
        if (order.requestedStartAt() == null || order.requestedEndAt() == null) {
            return "не указано";
        }
        return TIME.format(order.requestedStartAt()) + " - " + TIME.format(order.requestedEndAt());
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private boolean isHostessChat(Long chatId) {
        return chatId != null && hostessChatId != null && !hostessChatId.isBlank() && hostessChatId.equals(chatId.toString());
    }

    void setHostessChatId(String hostessChatId) {
        this.hostessChatId = hostessChatId;
    }

    void setTelegramEnabled(boolean telegramEnabled) {
        this.telegramEnabled = telegramEnabled;
    }
}
