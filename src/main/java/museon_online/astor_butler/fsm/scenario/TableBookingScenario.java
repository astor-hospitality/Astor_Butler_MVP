package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.media.AerisMediaCatalog;
import museon_online.astor_butler.domain.media.MediaAsset;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.InputIntent;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class TableBookingScenario implements FsmScenario {

    private static final DateTimeFormatter DATE_BUTTON = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter TIME_BUTTON = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale RU = Locale.forLanguageTag("ru-RU");
    private static final java.util.Set<String> LEAVE_WORDS = java.util.Set.of(
            "отмена", "стоп", "главное меню", "выйти", "передумал", "передумала", "не надо", "не нужно");

    /** The largest party the draft reader takes for one table, see TableBookingDraftMerger. */
    private static final int MAX_PARTY_SIZE = 20;
    private static final Pattern GUEST_COUNT_REPLY = Pattern.compile("(?:на\\s+|нас\\s+)?(\\d{2,4})(?:\\s*(?:гост\\p{L}*|человек\\p{L}*|персон\\p{L}*|чел\\.?))?");

    private final FSMStorage fsmStorage;
    private final TableBookingDraftStorage draftStorage;
    private final TableReservationService tableReservationService;
    private final AerisMediaCatalog mediaCatalog;
    private final TableBookingDraftMerger draftMerger;
    private final TableBookingStepRegistry stepRegistry;
    private final BookingPhraseService phraseService;
    private final BookingTimeProvider timeProvider;
    private final VenueOpeningHours openingHours;

    @Value("${telegram.booking.plan-pdf-asset-code:AERIS_FLOOR_PLAN}")
    private String planPdfAssetCode;

    @Value("${telegram.booking.manager-chat-id:876857557}")
    private Long managerTelegramId;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    public String id() {
        return "TABLE_BOOKING";
    }

    public int priority() {
        return 30;
    }

    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        return supports(incoming, currentState, text, null);
    }

    public boolean supports(IncomingMessage incoming, BotState currentState, String text, UnderstoodInput understood) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        return isTableBookingState(state) || isTableBookingIntent(text, understood);
    }

    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        return handle(incoming, currentState, text, null);
    }

    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text, UnderstoodInput understood) {
        String normalized = normalize(text);
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (isTableBookingState(state) && wantsToLeave(normalized)) {
            return leave(incoming);
        }
        TableBookingDraftStorage.Draft draft = draftMerger.merge(incoming, state, normalized, understood);

        if (draft.requestedDate() != null && draft.requestedTime() != null) {
            if (alreadyPassed(draft)) {
                Optional<TableBookingDraftStorage.Draft> tonight = laterTonight(draft);
                if (tonight.isEmpty()) {
                    return askForAnotherTime(incoming, state, draft, "Это время уже прошло.", "TIME_ALREADY_PASSED");
                }
                draft = tonight.get();
                draftStorage.save(incoming.chatId(), draft);
            }
            if (!openingHours.isOpen(draft.requestedDate(), draft.requestedTime())) {
                return askForAnOpenHour(incoming, state, draft);
            }
        }
        Optional<TableBookingStepRegistry.Step> nextStep = stepRegistry.nextMissingStep(draft);
        if (nextStep.isPresent()) {
            return askForStep(incoming, state, draft, nextStep.get(), normalized);
        }
        return createReservation(incoming, draft);
    }

    /**
     * The guest named a time when the venue is closed. The time is dropped, the guest is told the hours of that day,
     * and the booking goes on with whatever is still missing, which now includes the time.
     */
    private OutgoingMessage askForAnOpenHour(IncomingMessage incoming, BotState state, TableBookingDraftStorage.Draft draft) {
        String hours = openingHours.describe(draft.requestedDate())
                .map(open -> "В это время AERIS закрыт. В этот день ждем гостей " + open + ".")
                .orElse("В это время AERIS закрыт.");
        return askForAnotherTime(incoming, state, draft, hours, "TIME_OUTSIDE_OPENING_HOURS");
    }

    private boolean alreadyPassed(TableBookingDraftStorage.Draft draft) {
        Instant startAt = draft.requestedDate().atTime(draft.requestedTime()).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        return !startAt.isAfter(timeProvider.now());
    }

    /**
     * "Сегодня в 00:30", said in the evening, is half past midnight of the coming night: a late hour of today's own
     * evening, which the calendar puts on tomorrow. Any other time that is already gone is not guessed. Neither is
     * a late hour named after midnight, while last evening is still going on: at 00:24 "сегодня в 00:09" is a slip,
     * not a wish for the next night.
     */
    private Optional<TableBookingDraftStorage.Draft> laterTonight(TableBookingDraftStorage.Draft draft) {
        LocalDate today = timeProvider.today();
        if (!draft.requestedDate().equals(today)
                || !openingHours.isLateHourOf(today, draft.requestedTime())
                || openingHours.isLateHourOf(today.minusDays(1), timeProvider.nowTime())) {
            return Optional.empty();
        }
        LocalDate date = draft.requestedDate().plusDays(1);
        Instant startAt = date.atTime(draft.requestedTime()).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        Duration length = draft.requestedStartAt() == null || draft.requestedEndAt() == null
                ? Duration.ofHours(2)
                : Duration.between(draft.requestedStartAt(), draft.requestedEndAt());
        return Optional.of(new TableBookingDraftStorage.Draft(
                draft.venueCode(),
                startAt,
                startAt.plus(length),
                date,
                draft.requestedTime(),
                draft.partySize(),
                draft.tableCode(),
                draft.preferredZone(),
                draft.seatingPreference(),
                draft.seatingPreferenceResolved(),
                draft.originalText()
        ));
    }

    /** The time was understood but cannot be booked. It is dropped, the guest is told why, and the booking goes on. */
    private OutgoingMessage askForAnotherTime(IncomingMessage incoming, BotState state, TableBookingDraftStorage.Draft draft, String reason, String marker) {
        TableBookingDraftStorage.Draft withoutTime = new TableBookingDraftStorage.Draft(
                draft.venueCode(),
                null,
                null,
                draft.requestedDate(),
                null,
                draft.partySize(),
                draft.tableCode(),
                draft.preferredZone(),
                draft.seatingPreference(),
                draft.seatingPreferenceResolved(),
                draft.originalText()
        );
        draftStorage.save(incoming.chatId(), withoutTime);
        TableBookingStepRegistry.Step next = stepRegistry.nextMissingStep(withoutTime).orElseThrow();
        // An empty guest text keeps "не хочу гадать со временем" out: the time was understood, the venue is just closed then.
        OutgoingMessage question = askForStep(incoming, state, withoutTime, next, "");
        return new OutgoingMessage(
                question.channel(),
                question.externalUserId(),
                question.chatId(),
                reason + "\n\n" + question.text(),
                question.nextState(),
                question.html(),
                question.requestContact(),
                question.removeKeyboard(),
                question.fallback(),
                question.adminAlert(),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(marker), question.actions().stream()).toList(),
                question.metadata(),
                question.createdAt()
        );
    }

    /** The guest changed their mind in the middle of the booking: nothing is kept, nothing is created. */
    private OutgoingMessage leave(IncomingMessage incoming) {
        draftStorage.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return message(
                incoming,
                "Хорошо, бронь не оформляю. Главное меню оставил под рукой.",
                BotState.READY_FOR_DIALOG,
                "TABLE_BOOKING_CANCELLED_BY_GUEST",
                "RETURN_MAIN_MENU"
        );
    }

    /**
     * Only a whole reply counts, so "на двоих, без отмены" stays an answer. "Нет" is not here: that is how a guest
     * declines a seating wish.
     */
    private boolean wantsToLeave(String normalized) {
        String words = normalized.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
        return LEAVE_WORDS.contains(words) || words.startsWith("отменить") || words.startsWith("отмени ");
    }

    private OutgoingMessage askForStep(
            IncomingMessage incoming,
            BotState currentState,
            TableBookingDraftStorage.Draft draft,
            TableBookingStepRegistry.Step step,
            String normalizedText
    ) {
        BotState nextState = step.state();
        fsmStorage.setState(incoming.chatId(), nextState);
        String text = unusedAnswer(currentState, nextState, draft, normalizedText)
                .orElseGet(() -> withAcknowledgement(currentState, nextState, draft, normalizedText, phraseService.ask(step, draft)));

        if (nextState == BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION) {
            boolean includeDocument = shouldSendPlan(currentState);
            OutgoingMessage message = message(
                    incoming,
                    includeDocument ? text : withAcknowledgement(currentState, nextState, draft, normalizedText, existingPlanPrompt()),
                    nextState,
                    includeDocument ? "SEND_HALL_PLAN" : "USE_EXISTING_HALL_PLAN",
                    step.action()
            ).withRemoveKeyboard(true);
            return includeDocument ? withHallPlan(message) : message;
        }

        OutgoingMessage message = message(incoming, text, nextState, step.action());
        if (nextState == BotState.TABLE_BOOKING_COLLECT_DATE) {
            return message.withMetadata(Map.of("replyKeyboardRows", dateKeyboardRows()));
        }
        if (nextState == BotState.TABLE_BOOKING_COLLECT_TIME) {
            return message.withMetadata(Map.of("replyKeyboardRows", timeKeyboardRows(draft.requestedDate())));
        }
        if (nextState == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE) {
            return message.withRemoveKeyboard(true);
        }
        return message;
    }

    /**
     * The guest answered the question and the answer could not be used. The same question word for word reads as if
     * the bot had not heard, so the reason comes first. The time step has its own words in the acknowledgement.
     */
    private Optional<String> unusedAnswer(BotState currentState, BotState nextState, TableBookingDraftStorage.Draft draft, String normalizedText) {
        if (normalizedText.isBlank() || currentState != nextState) {
            return Optional.empty();
        }
        if (nextState == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE && draft.partySize() == null) {
            return Optional.of(namesTooManyGuests(normalizedText)
                    ? "В одну бронь стола могу записать до " + MAX_PARTY_SIZE + " гостей. Для большей компании напишите «менеджер», команда AERIS подберет вариант."
                    : "Не понял, сколько будет гостей. Напишите число, например 4.");
        }
        if (nextState == BotState.TABLE_BOOKING_COLLECT_DATE && draft.requestedDate() == null) {
            return Optional.of(normalizedText.contains("вчера")
                    ? "Этот день уже прошел. На какой день держим стол?"
                    : "Не смог понять день. Выберите его кнопкой или напишите, например, «завтра», «в пятницу», «30.06».");
        }
        return Optional.empty();
    }

    /** Only a reply that is a count of guests and nothing else: "21.06" at this step is a date, not twenty-one guests. */
    private boolean namesTooManyGuests(String normalizedText) {
        Matcher count = GUEST_COUNT_REPLY.matcher(normalizedText.trim());
        return count.matches() && Integer.parseInt(count.group(1)) > MAX_PARTY_SIZE;
    }

    private String withAcknowledgement(
            BotState currentState,
            BotState nextState,
            TableBookingDraftStorage.Draft draft,
            String normalizedText,
            String prompt
    ) {
        String acknowledgement = acknowledgement(currentState, nextState, draft, normalizedText);
        return acknowledgement.isBlank() ? prompt : acknowledgement + "\n\n" + prompt;
    }

    private String acknowledgement(
            BotState currentState,
            BotState nextState,
            TableBookingDraftStorage.Draft draft,
            String normalizedText
    ) {
        if (currentState == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE && draft.partySize() != null) {
            return "Принял, на " + draft.partySize() + " " + guestWord(draft.partySize()) + ".";
        }
        if (currentState == BotState.TABLE_BOOKING_COLLECT_DATE && draft.requestedDate() != null) {
            return "Принял, держим дату " + draft.requestedDate().format(DateTimeFormatter.ofPattern("dd.MM")) + ".";
        }
        if (currentState == BotState.TABLE_BOOKING_COLLECT_TIME) {
            if (draft.requestedTime() != null) {
                return "Хорошо, на " + draft.requestedTime().format(TIME_BUTTON) + ".";
            }
            if (!normalizedText.isBlank() && nextState == BotState.TABLE_BOOKING_COLLECT_TIME) {
                return "Не хочу гадать со временем, чтобы не поставить бронь не туда.";
            }
        }
        if (currentState == BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION
                && (draft.tableCode() != null || draft.preferredZone() != null || draft.seatingPreference() != null)) {
            return "Отлично, место отметил.";
        }
        return "";
    }

    private String guestWord(int value) {
        int mod10 = value % 10;
        int mod100 = value % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "гостя";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "гостей";
        }
        return "гостей";
    }

    private String existingPlanPrompt() {
        return "План уже перед вами. Напишите номер стола, зону или «подбери сам». Я проверю свободные варианты и начну с лучших столов под вашу компанию.";
    }

    private boolean shouldSendPlan(BotState state) {
        BotState canonical = state == null ? BotState.UNKNOWN : state.canonical();
        return canonical != BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION;
    }

    private OutgoingMessage withHallPlan(OutgoingMessage message) {
        MediaAsset floorPlan = mediaCatalog.floorPlan();
        return message.withMetadata(Map.of(
                "documentAssetCode", planPdfAssetCode,
                "documentObjectKey", floorPlan.objectKey(),
                "documentFilename", floorPlan.filename(),
                "documentCaption", floorPlan.title()
        ));
    }

    private List<List<String>> dateKeyboardRows() {
        LocalDate today = timeProvider.today();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            LocalDate date = today.plusDays(i);
            labels.add(dateLabel(date, i));
        }
        return rows(labels, 3);
    }

    private String dateLabel(LocalDate date, int offset) {
        if (offset == 0) {
            return "Сегодня " + date.format(DATE_BUTTON);
        }
        if (offset == 1) {
            return "Завтра " + date.format(DATE_BUTTON);
        }
        String weekday = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, RU);
        return capitalize(weekday.replace(".", "")) + " " + date.format(DATE_BUTTON);
    }

    private List<List<String>> timeKeyboardRows(LocalDate requestedDate) {
        LocalTime start = timeProvider.nextWholeHour();
        if (requestedDate != null && requestedDate.isAfter(timeProvider.today())) {
            start = LocalTime.of(12, 0);
        }
        LocalDate day = requestedDate == null ? timeProvider.today() : requestedDate;
        List<String> labels = new ArrayList<>();
        LocalTime time = start;
        for (int i = 0; i < 12; i++) {
            // Only hours of that same day when the venue is open: a button past midnight would mean the morning already gone.
            if (openingHours.isOpen(day, time)) {
                labels.add(time.format(TIME_BUTTON));
            }
            if (time.plusHours(1).isBefore(time)) {
                break;
            }
            time = time.plusHours(1);
        }
        return rows(labels, 4);
    }

    private List<List<String>> rows(List<String> labels, int columns) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i += columns) {
            rows.add(List.copyOf(labels.subList(i, Math.min(i + columns, labels.size()))));
        }
        return rows;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(RU) + value.substring(1);
    }

    private OutgoingMessage createReservation(IncomingMessage incoming, TableBookingDraftStorage.Draft draft) {
        Optional<TableReservationOrder> held = tableReservationService.findOverlappingReservation(
                incoming.chatId(), draft.venueCode(), draft.requestedStartAt(), draft.requestedEndAt());
        if (held.isPresent()) {
            return alreadyBooked(incoming, held.get());
        }
        try {
            TableReservationOrder order = tableReservationService.createReservation(new TableReservationCommand(
                    incoming.chatId(),
                    incoming.telegramUserId(),
                    null,
                    draft.venueCode(),
                    draft.tableCode(),
                    draft.preferredZone(),
                    draft.seatingPreference(),
                    draft.requestedStartAt(),
                    draft.requestedEndAt(),
                    draft.partySize(),
                    guestName(incoming),
                    incoming.contactPhone(),
                    draft.originalText(),
                    managerTelegramId,
                    hostessChatId
            ));
            draftStorage.clear(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return message(
                    incoming,
                    """
                    Готово. Заявку #%s передал команде AERIS на подтверждение. Как только хостес ответит, я вернусь с финальным статусом.

                    %s

                    Пока стол держат, могу показать меню или тихо подсказать актуальное: с воскресенья по четверг в AERIS действует винный безлимит за 1700 ₽, а на пятницу и субботу я подскажу ближайшую афишу недели.
                    """.formatted(order.id(), whatWasBooked(order)),
                    BotState.READY_FOR_DIALOG,
                    "RESERVATION_CREATED",
                    "WAIT_HOSTESS_CONFIRMATION",
                    "RETURN_MAIN_MENU"
            );
        } catch (ApiException e) {
            log.warn("Table booking reservation was not created: chatId={}, reason={}", incoming.chatId(), e.getMessage());
            fsmStorage.setState(incoming.chatId(), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION);
            return message(
                    incoming,
                    "Этот вариант сейчас не получается поставить в бронь. Выберите другой стол на плане или напишите «выбери сам» — подберу свободный вариант.",
                    BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                    "TABLE_SELECTION_REJECTED",
                    "ASK_TABLE_SELECTION"
            );
        }
    }

    /** What went to the hostess, so the guest can see it and catch a mistake: the table, the day and time, the party. */
    private String whatWasBooked(TableReservationOrder order) {
        List<String> lines = new ArrayList<>();
        String table = order.tableDisplayName() == null || order.tableDisplayName().isBlank()
                ? (order.tableCode() == null || order.tableCode().isBlank() ? "" : "Стол " + order.tableCode())
                : order.tableDisplayName().trim();
        if (table.startsWith("Table ")) {
            table = "Стол " + table.substring("Table ".length());
        }
        if (!table.isBlank()) {
            lines.add(table);
        }
        if (order.requestedStartAt() != null) {
            java.time.ZonedDateTime startAt = order.requestedStartAt().atZone(BookingTimeProvider.VENUE_ZONE);
            lines.add(startAt.format(DATE_BUTTON) + " в " + startAt.format(TIME_BUTTON));
        }
        if (order.partySize() != null) {
            lines.add("Гостей: " + order.partySize());
        }
        return String.join("\n", lines);
    }

    /** One guest cannot sit at two tables at once, so a second request for an overlapping time is not created. */
    private OutgoingMessage alreadyBooked(IncomingMessage incoming, TableReservationOrder held) {
        draftStorage.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        java.time.ZonedDateTime startAt = held.requestedStartAt().atZone(BookingTimeProvider.VENUE_ZONE);
        return message(
                incoming,
                "У вас уже есть заявка #%s на %s в %s, вторую на это же время не создаю. Изменить или отменить ее можно кнопкой «Изменить / отменить». Если нужен еще один стол, напишите «менеджер»."
                        .formatted(held.id(), startAt.format(DATE_BUTTON), startAt.format(TIME_BUTTON)),
                BotState.READY_FOR_DIALOG,
                "RESERVATION_ALREADY_EXISTS",
                "RETURN_MAIN_MENU"
        );
    }

    private OutgoingMessage message(IncomingMessage incoming, String text, BotState nextState, String... actions) {
        return OutgoingMessage.of(
                incoming,
                text,
                nextState.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of(actions)
        );
    }

    private boolean isTableBookingIntent(String text, UnderstoodInput understood) {
        if (understood != null && understood.primaryIntent() == InputIntent.TABLE_BOOKING) {
            return true;
        }
        String value = normalize(text);
        return value.equals("бронь")
                || value.equals("бронь стола")
                || value.contains("забронировать стол")
                || value.contains("забронировать столик")
                || value.contains("бронь стол")
                || value.contains("бронь столик")
                || value.contains("столик")
                || value.contains("стол на")
                || value.contains("есть места");
    }

    private boolean isTableBookingState(BotState state) {
        return switch (state) {
            case TABLE_BOOKING_INTENT,
                 TABLE_BOOKING_COLLECT_DATE,
                 TABLE_BOOKING_COLLECT_TIME,
                 TABLE_BOOKING_COLLECT_PARTY_SIZE,
                 TABLE_BOOKING_COLLECT_SEATING_PREFERENCE,
                 TABLE_BOOKING_SHOW_PLAN,
                 TABLE_BOOKING_WAIT_TABLE_SELECTION -> true;
            default -> false;
        };
    }

    public boolean owns(BotState state) {
        return state != null && isTableBookingState(state.canonical());
    }

    public boolean sideEffecting() {
        return true;
    }

    private String guestName(IncomingMessage incoming) {
        String firstName = incoming.firstName() == null ? "" : incoming.firstName().trim();
        String lastName = incoming.lastName() == null ? "" : incoming.lastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? incoming.username() : fullName;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase().replace('ё', 'е');
    }
}
