package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.identity.IdentityService;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
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

    private static final String SEND_BUTTON = "✅ Отправить заявку";
    private static final String CHANGE_BUTTON = "✏️ Изменить";
    /** The words a reply to the summary must consist of to send it. "Да, но позже" has other words in it and sends nothing. */
    private static final Set<String> YES_WORDS = Set.of(
            "да", "ага", "угу", "ок", "окей", "хорошо", "ладно", "конечно", "верно", "правильно", "точно", "так", "отлично",
            "подходит", "устраивает", "годится", "согласен", "согласна", "подтверждаю", "подтверждаем", "подтвердить",
            "отправить", "отправляй", "отправляйте", "отправь", "отправьте", "бронируй", "бронируйте", "оформляй", "оформляйте",
            "давай", "давайте");
    private static final Set<String> YES_SIGNS = Set.of("+", "👍", "👌", "✅");
    private static final Set<String> POLITE_WORDS = Set.of("все", "заявку", "бронь", "пожалуйста", "спасибо");
    /** The venue's own names of its zones, for a guest who asked for one of them in the same phrase as the booking. */
    private static final Map<String, String> ZONE_WORDS = Map.of(
            "WINDOW", "у окна",
            "WINE_ROOM", "винная комната",
            "BAR", "у бара",
            "VIP_ZONE", "VIP-зона",
            "CENTER_STAGE", "центр зала",
            "SOFT_LOUNGE", "камерная гостиная",
            "CORNER", "угловой столик");
    /** Whole replies that decline the summary. Elsewhere in the booking "нет" declines a seating wish, so it is not a leave word. */
    private static final Set<String> NO_REPLIES = Set.of("нет", "неа", "не", "нет спасибо", "не хочу", "не буду", "не сейчас");
    /** A wish written under the summary is kept among the guest's own words under this mark, which is how the hostess reads it. */
    private static final String WISH_MARK = "Пожелание: ";
    private static final int WISH_LIMIT = 200;
    /** A reply longer than this is a wish and is not searched for a day, a time or a number. */
    private static final int CORRECTION_WORDS = 4;
    private static final String STARTING_OVER = "Хорошо, соберем заново.";

    private final FSMStorage fsmStorage;
    private final TableBookingDraftStorage draftStorage;
    private final TableReservationService tableReservationService;
    private final AerisMediaCatalog mediaCatalog;
    private final TableBookingDraftMerger draftMerger;
    private final TableBookingStepRegistry stepRegistry;
    private final BookingPhraseService phraseService;
    private final BookingTimeProvider timeProvider;
    private final VenueOpeningHours openingHours;
    private final IdentityService identityService;

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
        if (state == BotState.TABLE_BOOKING_CONFIRMATION) {
            return onSummaryReply(incoming, normalized, understood);
        }
        return proceed(incoming, state, draftMerger.merge(incoming, state, normalized, understood), normalized, false, "");
    }

    /**
     * Checks the time against the clock and the opening hours, asks for whatever is still missing, and with nothing
     * missing shows the summary. The request is created only when the guest has already said yes to that summary.
     */
    private OutgoingMessage proceed(
            IncomingMessage incoming,
            BotState state,
            TableBookingDraftStorage.Draft known,
            String normalized,
            boolean confirmed,
            String lead
    ) {
        TableBookingDraftStorage.Draft draft = known;
        if (draft.requestedDate() != null && draft.requestedTime() != null) {
            if (alreadyPassed(draft)) {
                // The guest said yes to the night in the summary. It is not moved to the next one behind their back.
                Optional<TableBookingDraftStorage.Draft> tonight = confirmed ? Optional.empty() : laterTonight(draft);
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
        return confirmed ? createReservation(incoming, draft) : askForConfirmation(incoming, draft, lead);
    }

    /**
     * Everything is known. The guest reads what will go to the hostess and decides: until the yes nothing is created.
     * The lines are the ones the "Готово" message repeats afterwards.
     */
    private OutgoingMessage askForConfirmation(IncomingMessage incoming, TableBookingDraftStorage.Draft draft, String lead) {
        // A second table for a time the guest already holds is refused when it is created; better to say so before the yes.
        Optional<TableReservationOrder> held = tableReservationService.findOverlappingReservation(
                incoming.chatId(), draft.venueCode(), draft.requestedStartAt(), draft.requestedEndAt());
        if (held != null && held.isPresent()) {
            return alreadyBooked(incoming, held.get());
        }
        fsmStorage.setState(incoming.chatId(), BotState.TABLE_BOOKING_CONFIRMATION);
        String question = """
                Проверьте, пожалуйста, бронь:

                %s

                Отправляю заявку команде AERIS? Если что-то не так, напишите коротко: «в 20:00», «на троих», «стол 5». \
                Пожелания тоже можно написать одной строкой, я передам.""".formatted(summary(draft));
        return message(
                incoming,
                lead == null || lead.isBlank() ? question : lead + "\n\n" + question,
                BotState.TABLE_BOOKING_CONFIRMATION,
                "ASK_BOOKING_CONFIRMATION"
        ).withMetadata(Map.of("replyKeyboardRows", List.of(List.of(SEND_BUTTON), List.of(CHANGE_BUTTON))));
    }

    private String summary(TableBookingDraftStorage.Draft draft) {
        List<String> lines = new ArrayList<>();
        lines.add(draft.tableCode() == null || draft.tableCode().isBlank() ? "Стол: подберем свободный" : "Стол " + draft.tableCode().trim());
        lines.add(draft.requestedDate().format(DATE_BUTTON) + " в " + draft.requestedTime().format(TIME_BUTTON));
        lines.add("Гостей: " + draft.partySize());
        String wish = wish(draft);
        if (!wish.isBlank()) {
            lines.add(WISH_MARK + wish);
        }
        return String.join("\n", lines);
    }

    /** What the guest asked for besides the table: the words about the seating, and the wishes written under the summary. */
    private String wish(TableBookingDraftStorage.Draft draft) {
        List<String> wishes = new ArrayList<>();
        String seating = draft.seatingPreference() == null ? "" : draft.seatingPreference().trim();
        // "Подбери сам" on its own is the choice of a table. Said with more words, the rest is still a wish.
        boolean onlyLeavesTheChoice = stepRegistry.leavesTheChoiceToUs(seating) && seating.split("\\s+").length <= 3;
        if (!seating.isBlank() && !onlyLeavesTheChoice) {
            // A booking asked in one phrase is kept whole as the seating wish: "стол завтра в 20:00 на 2 гостей у окна".
            // The day, the time and the party are already in the lines above, so only the zone is repeated, in the venue's words.
            TableBookingDraftStorage.Draft inTheWish = draftMerger.read(Optional.empty(), seating, BotState.TABLE_BOOKING_INTENT, seating, null);
            boolean wholePhrase = inTheWish.requestedDate() != null || inTheWish.requestedTime() != null || inTheWish.partySize() != null;
            String shown = wholePhrase ? ZONE_WORDS.getOrDefault(draft.preferredZone() == null ? "" : draft.preferredZone(), "") : seating;
            if (!shown.isBlank()) {
                wishes.add(shown);
            }
        }
        String guestWords = draft.originalText() == null ? "" : draft.originalText();
        for (String said : guestWords.split(" \\| ")) {
            if (said.startsWith(WISH_MARK)) {
                wishes.add(said.substring(WISH_MARK.length()).trim());
            }
        }
        return String.join("; ", wishes);
    }

    /**
     * The guest is reading the summary. A plain yes sends it, "нет" leaves the booking, "изменить" starts it over.
     * A short reply that names a day, a time, a party or a table corrects the summary. Anything else is a wish for the
     * team: it is kept in the guest's own words and is not searched for numbers, so "один гость не ест лук" does not
     * turn the party into one guest. Whatever the reply, nothing is created without the yes.
     */
    private OutgoingMessage onSummaryReply(IncomingMessage incoming, String normalized, UnderstoodInput understood) {
        BotState state = BotState.TABLE_BOOKING_CONFIRMATION;
        // The router may hand over a rewritten phrase ("на троих" as "на 3 гостей"); a yes, a no and a wish are the guest's own words.
        String said = incoming.text() == null || incoming.text().isBlank() ? normalized : incoming.text().trim();
        String reply = words(normalize(said));
        if (declines(reply)) {
            return leave(incoming);
        }
        Optional<TableBookingDraftStorage.Draft> stored = draftStorage.find(incoming.chatId());
        if (stored == null || stored.isEmpty()) {
            // The draft is kept for a day. Without it there is nothing to send, so nothing is sent.
            return startOver(incoming, asksToStartOver(reply)
                    ? STARTING_OVER
                    : "Данные этой брони не сохранились, заявку я не отправлял. Давайте соберем ее заново.");
        }
        // Read with no words on top: a draft may hold only the start of the visit, without the day and the time apart.
        TableBookingDraftStorage.Draft draft = draftMerger.read(stored, "", state, "", null);
        if (stepRegistry.nextMissingStep(draft).isPresent()) {
            return proceed(incoming, state, draftMerger.merge(incoming, state, normalized, understood), normalized, false, "");
        }
        if (draft.requestedDate().isBefore(timeProvider.today())) {
            // No hour of that day is left to offer, so asking for another time would refuse every answer.
            return startOver(incoming, "День этой брони уже прошел, заявку я не отправлял. Давайте соберем ее заново.");
        }
        if (isYes(reply) || YES_SIGNS.contains(said)) {
            return proceed(incoming, state, draft, "", true, "");
        }
        if (reply.isBlank() || POLITE_WORDS.containsAll(List.of(reply.split(" ")))) {
            // An emoji or a "спасибо" answers nothing and is not a wish to pass on.
            return askForConfirmation(incoming, draft, "");
        }
        // "21" and "на 21" under the summary are nine in the evening; said with guests, or too large for an hour, it is a party.
        if (namesTooManyGuests(normalized) && !normalized.trim().matches("(?:на\\s+)?(?:1\\d|2[0-3])")) {
            return askForConfirmation(incoming, draft, tooManyGuests());
        }
        if (said.split("\\s+").length <= CORRECTION_WORDS) {
            TableBookingDraftStorage.Draft corrected = draftMerger.read(stored, incoming.text(), state, normalized, understood);
            boolean changed = !sameVisit(draft, corrected);
            if (changed || namesTheVisit(draftMerger.read(Optional.empty(), incoming.text(), state, normalized, understood))) {
                draftStorage.save(incoming.chatId(), corrected);
                return proceed(incoming, state, corrected, normalized, false, changed ? "Поправил." : "");
            }
            // "Нет, спасибо", "хочу отменить": said in a few words and with nothing to correct, it is a no.
            if (reply.startsWith("нет ") || reply.contains("отмен")) {
                return leave(incoming);
            }
        }
        // A longer reply is a no only when it says so outright: "нет, лучше в субботу вечером, часов в семь" must not lose the draft.
        if (reply.startsWith("отмен") || reply.contains("передумал")) {
            return leave(incoming);
        }
        if (asksToStartOver(reply)) {
            return startOver(incoming, STARTING_OVER);
        }
        TableBookingDraftStorage.Draft withWish = withWish(draft, said);
        draftStorage.save(incoming.chatId(), withWish);
        return askForConfirmation(incoming, withWish, "Пожелание записал.");
    }

    /** Only words of agreement and nothing else: "да, все верно", "отправляйте, пожалуйста". */
    private boolean isYes(String reply) {
        List<String> said = java.util.Arrays.stream(reply.split(" ")).filter(word -> !POLITE_WORDS.contains(word)).toList();
        return !said.isEmpty() && YES_WORDS.containsAll(said);
    }

    private boolean declines(String reply) {
        return NO_REPLIES.contains(reply) || reply.startsWith("не отправ");
    }

    private boolean asksToStartOver(String reply) {
        return reply.startsWith("изменить")
                || reply.startsWith("поменять")
                || reply.startsWith("исправить")
                || reply.contains("заново")
                || reply.equals("сначала")
                || reply.equals("начать сначала");
    }

    private boolean sameVisit(TableBookingDraftStorage.Draft one, TableBookingDraftStorage.Draft other) {
        return Objects.equals(one.requestedDate(), other.requestedDate())
                && Objects.equals(one.requestedTime(), other.requestedTime())
                && Objects.equals(one.partySize(), other.partySize())
                && Objects.equals(one.tableCode(), other.tableCode())
                && Objects.equals(one.preferredZone(), other.preferredZone())
                && Objects.equals(one.seatingPreference(), other.seatingPreference());
    }

    /** Whether a reply, read on its own, says anything about the visit: "в 20:00" does even when the summary already says 20:00. */
    private boolean namesTheVisit(TableBookingDraftStorage.Draft said) {
        return said.requestedDate() != null
                || said.requestedTime() != null
                || said.partySize() != null
                || said.tableCode() != null
                || said.preferredZone() != null
                || said.seatingPreference() != null;
    }

    private TableBookingDraftStorage.Draft withWish(TableBookingDraftStorage.Draft draft, String said) {
        String wish = said.replace("|", "/").replaceAll("\\s+", " ").trim();
        String note = WISH_MARK + (wish.length() > WISH_LIMIT ? wish.substring(0, WISH_LIMIT) : wish);
        String guestWords = draft.originalText() == null ? "" : draft.originalText();
        return new TableBookingDraftStorage.Draft(
                draft.venueCode(),
                draft.requestedStartAt(),
                draft.requestedEndAt(),
                draft.requestedDate(),
                draft.requestedTime(),
                draft.partySize(),
                draft.tableCode(),
                draft.preferredZone(),
                draft.seatingPreference(),
                draft.seatingPreferenceResolved(),
                guestWords.isBlank() ? note : guestWords.contains(note) ? guestWords : guestWords + " | " + note
        );
    }

    /** Drops what was collected and asks the first question again. Nothing is created. */
    private OutgoingMessage startOver(IncomingMessage incoming, String lead) {
        BotState state = BotState.TABLE_BOOKING_CONFIRMATION;
        draftStorage.clear(incoming.chatId());
        TableBookingDraftStorage.Draft nothing = draftMerger.read(Optional.empty(), "", state, "", null);
        OutgoingMessage question = askForStep(incoming, state, nothing, stepRegistry.nextMissingStep(nothing).orElseThrow(), "");
        return new OutgoingMessage(
                question.channel(),
                question.externalUserId(),
                question.chatId(),
                lead + "\n\n" + question.text(),
                question.nextState(),
                question.html(),
                question.requestContact(),
                question.removeKeyboard(),
                question.fallback(),
                question.adminAlert(),
                question.actions(),
                question.metadata(),
                question.createdAt()
        );
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
        String words = words(normalized);
        return LEAVE_WORDS.contains(words) || words.startsWith("отменить") || words.startsWith("отмени ");
    }

    /** The reply without emoji and punctuation, so a button and the same words typed by hand read alike. */
    private String words(String normalized) {
        return normalized.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
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
                    ? tooManyGuests()
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

    private String tooManyGuests() {
        return "В одну бронь стола могу записать до " + MAX_PARTY_SIZE + " гостей. Для большей компании напишите «менеджер», команда AERIS подберет вариант.";
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
                    guestPhone(incoming),
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
            // The buttons of the summary go away: "Отправить заявку" would only ask for the same table again.
            return message(
                    incoming,
                    "Этот вариант сейчас не получается поставить в бронь. Выберите другой стол на плане или напишите «выбери сам» — подберу свободный вариант.",
                    BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                    "TABLE_SELECTION_REJECTED",
                    "ASK_TABLE_SELECTION"
            ).withRemoveKeyboard(true);
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
                 TABLE_BOOKING_WAIT_TABLE_SELECTION,
                 TABLE_BOOKING_CONFIRMATION -> true;
            default -> false;
        };
    }

    public boolean owns(BotState state) {
        return state != null && isTableBookingState(state.canonical());
    }

    public boolean sideEffecting() {
        return true;
    }

    /**
     * The phone for the hostess card and the venue's own system. A contact in this very message wins; otherwise it is
     * the one the guest shared earlier in the dialogue, because the last step of a booking is plain text and Saby takes
     * no booking without a phone. Null when the guest never shared one: the request then stays with the hostess.
     */
    private String guestPhone(IncomingMessage incoming) {
        if (incoming.contactPhone() != null && !incoming.contactPhone().isBlank()) {
            return incoming.contactPhone();
        }
        return identityService.primaryPhone(incoming.telegramUserId()).orElse(null);
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
