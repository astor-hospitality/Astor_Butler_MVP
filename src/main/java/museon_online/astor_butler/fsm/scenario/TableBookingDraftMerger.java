package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.understanding.GuestPartyText;
import museon_online.astor_butler.fsm.understanding.SlotValue;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TableBookingDraftMerger {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    // Either a whole clock time, or a bare hour that is not a piece of a date or of a clock time that does not exist ("25:00").
    private static final Pattern TIME = Pattern.compile(
            "(?<![:./-])\\b(?:([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)|([01]?\\d|2[0-3])(?:\\s*(?:час(?:ов|а)?|ч))?\\b(?![:./-]))");
    private static final Pattern TABLE_NUMBER_SELECTION = Pattern.compile("^(?:стол(?:ик)?\\s*)?(?:[1-9]|1\\d)$");
    private static final Pattern TABLE_NUMBER_IN_TEXT = Pattern.compile(".*(?:^|\\s)стол(?:ик)?\\s*(?:[1-9]|1\\d)(?:\\s|$).*");
    private static final Pattern TABLE_NUMBER_BEFORE_WORD = Pattern.compile(".*(?:^|\\s)(?:[1-9]|1\\d)\\s*стол(?:ик)?(?:\\s|$).*");
    private static final Pattern SHORT_PARTY_SIZE_ANSWER = Pattern.compile("^(?:на\\s*)?(\\d{1,2})(?:\\s*(?:гостей|гостя|человек|персон|чел))?$");
    private static final String[] AUTO_SELECTION_PHRASES = {
            "выбери сам",
            "подбери сам",
            "сам подбери",
            "сам выбери",
            "любой стол",
            "любой подходящий",
            "на твой выбор",
            "на ваше усмотрение",
            "на усмотрение",
            "где удобно"
    };
    private final TableBookingDraftStorage draftStorage;
    private final BookingTimeProvider timeProvider;

    @Value("${astor.booking.default-venue-code:AERIS}")
    private String defaultVenueCode;

    public TableBookingDraftMerger(TableBookingDraftStorage draftStorage, BookingTimeProvider timeProvider) {
        this.draftStorage = draftStorage;
        this.timeProvider = timeProvider;
    }

    public TableBookingDraftStorage.Draft merge(
            IncomingMessage incoming,
            BotState currentState,
            String normalized,
            UnderstoodInput understood
    ) {
        TableBookingDraftStorage.Draft draft = read(findDraft(incoming.chatId()), incoming.text(), currentState, normalized, understood);
        draftStorage.save(incoming.chatId(), draft);
        return draft;
    }

    /**
     * What one message says about the visit, on top of what is already known: day, time, party size, table.
     * Reads and stores nothing, so another scenario can ask the same questions and hear the answers the same way.
     */
    public TableBookingDraftStorage.Draft read(
            Optional<TableBookingDraftStorage.Draft> stored,
            String rawText,
            BotState currentState,
            String normalized,
            UnderstoodInput understood
    ) {
        Map<String, SlotValue> slots = understood == null || understood.slots() == null ? Map.of() : understood.slots();

        Optional<LocalDate> extractedDate = dateFromSlot(slots).or(() -> extractDate(normalized));
        LocalDate date = extractedDate.or(() -> storedDate(stored)).orElse(null);

        Optional<LocalTime> slotTime = timeFromSlot(slots).map(time -> GuestDateText.atTimeOfDay(time, normalized).orElse(time));
        Optional<LocalTime> extractedTime = slotTime.isPresent()
                ? slotTime
                : shouldIgnoreTimeInCurrentStep(currentState, normalized, extractedDate)
                ? Optional.empty()
                : extractTime(normalized);
        LocalTime time = extractedTime.or(() -> storedTime(stored)).orElse(null);

        Integer partySize = partySizeFromSlot(slots)
                .or(() -> extractPartySize(normalized, currentState))
                .or(() -> stored.map(TableBookingDraftStorage.Draft::partySize))
                .orElse(null);

        boolean captureTableSelection = shouldCaptureTableSelection(currentState, normalized, slots);
        String tableCode = captureTableSelection
                ? tableCodeFromSlot(slots).orElseGet(() -> tableCode(normalized))
                : stored.map(TableBookingDraftStorage.Draft::tableCode).orElse(null);

        boolean captureSeatingPreference = shouldCaptureSeatingPreference(currentState, normalized, slots);
        String preferredZone = (captureTableSelection || captureSeatingPreference
                ? preferredZoneFromSlot(slots).or(() -> preferredZone(normalized))
                : Optional.<String>empty())
                .or(() -> stored.map(TableBookingDraftStorage.Draft::preferredZone))
                .orElse(null);
        String seatingPreference = (captureTableSelection || captureSeatingPreference
                ? seatingPreferenceFromSlot(slots).or(() -> seatingPreference(normalized, captureSeatingPreference))
                : Optional.<String>empty())
                .or(() -> stored.map(TableBookingDraftStorage.Draft::seatingPreference))
                .orElse(null);

        String originalText = mergeOriginalText(stored.map(TableBookingDraftStorage.Draft::originalText).orElse(null), rawText);
        Instant startAt = date == null || time == null ? null : date.atTime(time).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        return new TableBookingDraftStorage.Draft(
                defaultVenueCode,
                startAt,
                startAt == null ? null : startAt.plusSeconds(2 * 60 * 60),
                date,
                time,
                partySize,
                tableCode,
                preferredZone,
                seatingPreference,
                seatingPreferenceResolved(currentState, normalized, seatingPreference, stored, slots),
                originalText
        );
    }

    private Optional<LocalDate> dateFromSlot(Map<String, SlotValue> slots) {
        return slot(slots, "date").map(SlotValue::value).flatMap(this::extractDate);
    }

    private Optional<LocalTime> timeFromSlot(Map<String, SlotValue> slots) {
        return slot(slots, "time").map(SlotValue::value).flatMap(this::extractTime);
    }

    private Optional<Integer> partySizeFromSlot(Map<String, SlotValue> slots) {
        return slot(slots, "partySize").map(SlotValue::value).flatMap(value -> {
            try {
                int parsed = Integer.parseInt(value.trim());
                return parsed > 0 && parsed <= 20 ? Optional.of(parsed) : Optional.empty();
            } catch (NumberFormatException ignored) {
                return extractPartySize(normalize(value));
            }
        });
    }

    private Optional<String> tableCodeFromSlot(Map<String, SlotValue> slots) {
        return slot(slots, "tableNumber").map(SlotValue::value).map(String::trim).filter(value -> !value.isBlank());
    }

    private Optional<String> preferredZoneFromSlot(Map<String, SlotValue> slots) {
        return seatingPreferenceFromSlot(slots).flatMap(this::preferredZone);
    }

    private Optional<String> seatingPreferenceFromSlot(Map<String, SlotValue> slots) {
        return slot(slots, "seatingPreference")
                .map(SlotValue::value)
                .map(this::normalize)
                .filter(value -> !value.isBlank() && !isNoSeatingPreference(value));
    }

    private Optional<SlotValue> slot(Map<String, SlotValue> slots, String name) {
        return Optional.ofNullable(slots.get(name));
    }

    private boolean seatingPreferenceResolved(
            BotState currentState,
            String normalized,
            String seatingPreference,
            Optional<TableBookingDraftStorage.Draft> stored,
            Map<String, SlotValue> slots
    ) {
        if (seatingPreference != null && !seatingPreference.isBlank()) {
            return true;
        }
        if (stored.map(TableBookingDraftStorage.Draft::seatingPreferenceResolved).orElse(false)) {
            return true;
        }
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        return state == BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE
                && (isNoSeatingPreference(normalized) || seatingPreferenceFromSlot(slots).isEmpty() && slot(slots, "seatingPreference").isPresent());
    }

    private Optional<LocalDate> storedDate(Optional<TableBookingDraftStorage.Draft> stored) {
        return stored.flatMap(draft -> draft.requestedDate() == null
                ? Optional.ofNullable(draft.requestedStartAt()).map(startAt -> startAt.atZone(BookingTimeProvider.VENUE_ZONE).toLocalDate())
                : Optional.of(draft.requestedDate()));
    }

    private Optional<LocalTime> storedTime(Optional<TableBookingDraftStorage.Draft> stored) {
        return stored.flatMap(draft -> draft.requestedTime() == null
                ? Optional.ofNullable(draft.requestedStartAt()).map(startAt -> startAt.atZone(BookingTimeProvider.VENUE_ZONE).toLocalTime())
                : Optional.of(draft.requestedTime()));
    }

    private boolean shouldIgnoreTimeInCurrentStep(BotState currentState, String normalized, Optional<LocalDate> extractedDate) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE || state == BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE) {
            return true;
        }
        if (looksLikeTableSelection(normalized)) {
            return true;
        }
        return state == BotState.TABLE_BOOKING_COLLECT_DATE && extractedDate.isEmpty();
    }

    private boolean shouldCaptureTableSelection(BotState currentState, String normalized, Map<String, SlotValue> slots) {
        if (slots.containsKey("tableNumber")) {
            return true;
        }
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state == BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION || state == BotState.TABLE_BOOKING_SHOW_PLAN) {
            return looksLikeTableSelection(normalized);
        }
        return containsAny(normalized, "стол", "столик", "винн", "vip", "вип", "бар", "окн", "центр", "угл", "диван")
                || isAutoSelection(normalized);
    }

    private boolean shouldCaptureSeatingPreference(BotState currentState, String normalized, Map<String, SlotValue> slots) {
        if (slots.containsKey("seatingPreference")) {
            return true;
        }
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        return state == BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE
                || containsAny(normalized, "тих", "окн", "диван", "не проход", "уют", "винн", "vip", "вип", "бар", "центр", "угл");
    }

    private Optional<LocalDate> extractDate(String text) {
        if (ISO_DATE.matcher(text).matches()) {
            return GuestDateText.isoDate(text);
        }
        if (text.contains("послезавтра")) {
            return Optional.of(timeProvider.today().plusDays(2));
        }
        Optional<LocalDate> weekday = extractWeekdayDate(text);
        if (weekday.isPresent()) {
            return weekday;
        }
        if (text.contains("сегодня") || text.contains("завтра")) {
            return Optional.of(requestedDate(text));
        }
        return GuestDateText.dayMonth(text, timeProvider.today());
    }

    private Optional<LocalDate> extractWeekdayDate(String text) {
        DayOfWeek dayOfWeek = weekdayFromText(text).orElse(null);
        if (dayOfWeek == null) {
            return Optional.empty();
        }
        LocalDate today = timeProvider.today();
        LocalDate date = today.with(TemporalAdjusters.nextOrSame(dayOfWeek));
        return Optional.of(date);
    }

    private Optional<DayOfWeek> weekdayFromText(String text) {
        if (containsAny(text, "понедельник", "понедельника")) {
            return Optional.of(DayOfWeek.MONDAY);
        }
        if (containsAny(text, "вторник", "вторника")) {
            return Optional.of(DayOfWeek.TUESDAY);
        }
        if (containsAny(text, "среду", "среда", "среды")) {
            return Optional.of(DayOfWeek.WEDNESDAY);
        }
        if (containsAny(text, "четверг", "четверга")) {
            return Optional.of(DayOfWeek.THURSDAY);
        }
        if (containsAny(text, "пятницу", "пятница", "пятницы")) {
            return Optional.of(DayOfWeek.FRIDAY);
        }
        if (containsAny(text, "субботу", "суббота", "субботы")) {
            return Optional.of(DayOfWeek.SATURDAY);
        }
        if (containsAny(text, "воскресенье", "воскресенья")) {
            return Optional.of(DayOfWeek.SUNDAY);
        }
        return Optional.empty();
    }

    private Optional<LocalTime> extractTime(String text) {
        if (looksLikePartySizeAnswer(text) || looksLikeTableSelection(text)) {
            return Optional.empty();
        }
        // "19.30" is how many guests write a time; the TIME pattern deliberately skips digits next to a dot.
        Optional<LocalTime> dotted = GuestDateText.dottedTime(text, timeProvider.today());
        if (dotted.isPresent()) {
            return dotted.map(time -> atTimeOfDay(time, text));
        }
        Matcher matcher = TIME.matcher(text);
        return matcher.find() ? Optional.of(parseTime(matcher, text)) : Optional.empty();
    }

    private Optional<Integer> extractPartySize(String text) {
        Optional<Integer> family = GuestPartyText.adultsWithChildren(text);
        if (family.isPresent() || GuestPartyText.childrenWithoutACount(text)) {
            return family;
        }
        if (containsAny(text, "одного", "один", "одна", "одному", "соло", "я один", "я одна", "буду один", "буду одна", "только я")) {
            return Optional.of(1);
        }
        if (text.contains("двоих") || text.contains("двоем") || text.contains("двух") || text.contains("двое") || text.contains("двоем")) {
            return Optional.of(2);
        }
        if (text.contains("троих") || text.contains("трое") || text.contains("трех")) {
            return Optional.of(3);
        }
        if (text.contains("четверых") || text.contains("четверо") || text.contains("четырех")) {
            return Optional.of(4);
        }
        Matcher compactMatcher = Pattern.compile("(?:^|\\s)на\\s+(\\d{1,2})\\s*(?:x|х|-х|-x)(?:\\s|$)").matcher(text);
        if (compactMatcher.find()) {
            return Optional.of(Integer.parseInt(compactMatcher.group(1)));
        }
        Matcher guestMatcher = Pattern.compile("\\b(\\d{1,2})\\s*(?:гостей|гостя|человек|персон|чел)\\b").matcher(text);
        if (guestMatcher.find()) {
            return Optional.of(Integer.parseInt(guestMatcher.group(1)));
        }
        Matcher matcher = Pattern.compile("\\bна\\s+(\\d{1,2})\\b").matcher(text);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private Optional<Integer> extractPartySize(String text, BotState currentState) {
        Optional<Integer> explicit = extractPartySize(text);
        if (explicit.isPresent()) {
            return explicit;
        }
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state != BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE) {
            return Optional.empty();
        }
        Matcher shortAnswer = SHORT_PARTY_SIZE_ANSWER.matcher(text);
        if (!shortAnswer.matches()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(shortAnswer.group(1));
        return value > 0 && value <= 20 ? Optional.of(value) : Optional.empty();
    }

    private boolean looksLikePartySizeAnswer(String text) {
        return extractPartySize(text).isPresent()
                && !containsAny(text, ":", "вечер", "утр", "дня", "ноч", "час", "ч ")
                && extractDate(text).isEmpty();
    }

    private boolean looksLikeTableSelection(String text) {
        return isAutoSelection(text)
                || text.contains("vip")
                || text.contains("вип")
                || text.contains("винн")
                || text.contains("бар")
                || text.contains("окн")
                || text.contains("центр")
                || text.contains("угл")
                || text.contains("диван")
                || TABLE_NUMBER_SELECTION.matcher(text).matches()
                || TABLE_NUMBER_IN_TEXT.matcher(text).matches()
                || TABLE_NUMBER_BEFORE_WORD.matcher(text).matches();
    }

    private LocalDate requestedDate(String text) {
        LocalDate today = timeProvider.today();
        if (text.contains("послезавтра")) {
            return today.plusDays(2);
        }
        if (text.contains("завтра")) {
            return today.plusDays(1);
        }
        return GuestDateText.dayMonth(text, today).orElse(today);
    }

    private String tableCode(String text) {
        if (isAutoSelection(text)) {
            return null;
        }
        Matcher reverseMatcher = Pattern.compile("(?:^|\\s)(1\\d|[1-9])\\s*стол(?:ик)?(?:\\s|$)").matcher(text);
        if (reverseMatcher.find()) {
            return reverseMatcher.group(1);
        }
        Matcher matcher = Pattern.compile("(?:^|\\s)(?:стол(?:ик)?\\s*)?(1\\d|[1-9])(?:\\s|$)").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Optional<String> preferredZone(String text) {
        if (text.contains("vip") || text.contains("вип")) {
            return Optional.of("VIP_ZONE");
        }
        if (text.contains("wine") || text.contains("винн")) {
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

    private Optional<String> seatingPreference(String text, boolean allowFreeText) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (isAutoSelection(text)) {
            return Optional.of("подбери сам");
        }
        if (isNoSeatingPreference(text)) {
            return Optional.empty();
        }
        if (allowFreeText) {
            return Optional.of(text);
        }
        if (containsAny(text, "тих", "окн", "диван", "vip", "вип", "винн", "бар", "не проход", "уют", "центр", "угл", "блест")) {
            return Optional.of(text);
        }
        return Optional.empty();
    }

    private boolean isNoSeatingPreference(String text) {
        String normalized = normalize(text);
        return normalized.equals("нет")
                || normalized.equals("не")
                || normalized.equals("без")
                || normalized.equals("без пожеланий")
                || normalized.equals("пожеланий нет")
                || normalized.equals("нет пожеланий")
                || normalized.equals("любой")
                || normalized.equals("любой стол")
                || normalized.equals("как удобно")
                || normalized.equals("где удобно")
                || normalized.equals("на ваше усмотрение")
                || normalized.equals("на твой выбор")
                || normalized.equals("выбери сам")
                || normalized.equals("подбери сам")
                || normalized.equals("сам подбери")
                || normalized.equals("сам выбери");
    }

    private LocalTime parseTime(Matcher matcher, String text) {
        boolean clock = matcher.group(1) != null;
        int hour = Integer.parseInt(clock ? matcher.group(1) : matcher.group(3));
        int minute = clock ? Integer.parseInt(matcher.group(2)) : 0;
        return atTimeOfDay(LocalTime.of(hour, minute), text);
    }

    private LocalTime atTimeOfDay(LocalTime time, String text) {
        String normalized = normalize(text);
        // Without a word right after the time, "вечером в 7" still means the evening, as before.
        return GuestDateText.atTimeOfDay(time, normalized)
                .orElseGet(() -> time.getHour() >= 1 && time.getHour() <= 11 && normalized.contains("вечер") ? time.plusHours(12) : time);
    }

    private String mergeOriginalText(String existing, String next) {
        String safeNext = next == null ? "" : next.trim();
        if (existing == null || existing.isBlank()) {
            return safeNext;
        }
        if (safeNext.isBlank() || existing.contains(safeNext)) {
            return existing;
        }
        return existing + " | " + safeNext;
    }

    private Optional<TableBookingDraftStorage.Draft> findDraft(Long chatId) {
        Optional<TableBookingDraftStorage.Draft> draft = draftStorage.find(chatId);
        return draft == null ? Optional.empty() : draft;
    }

    private boolean containsAny(String text, String... variants) {
        for (String variant : variants) {
            if (text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAutoSelection(String text) {
        return containsAny(text, AUTO_SELECTION_PHRASES);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase().replace('ё', 'е');
    }
}
