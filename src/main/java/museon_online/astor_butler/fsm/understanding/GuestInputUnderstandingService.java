package museon_online.astor_butler.fsm.understanding;

import museon_online.astor_butler.domain.semantic.EmbeddingProvider;
import museon_online.astor_butler.domain.semantic.IntentExampleMatch;
import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.fsm.core.BotState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class GuestInputUnderstandingService {

    private static final Pattern COMPACT_PARTY_SIZE = Pattern.compile("(?:^|\\s)на\\s+(\\d{1,2})\\s*(?:x|х|-х|-x)(?:\\s|$)");
    private static final Pattern GUEST_PARTY_SIZE = Pattern.compile("(?:^|\\s)(\\d{1,2})\\s*(?:гостей|гостя|человек|персон|чел)");
    private static final Pattern EVENING_TIME = Pattern.compile("(?:^|\\s)(?:в\\s*)?([1-9]|1[0-1])\\s*(?:вечера|вечер)(?:\\s|$)");
    private static final Pattern HOUR_TIME = Pattern.compile("(?:^|\\s)(?:в|к)?\\s*([01]?\\d|2[0-3])\\s*(?:час(?:ов|а)?|ч)(?:\\s|$)");
    private static final Pattern AROUND_HOUR_TIME = Pattern.compile("(?:^|\\s)(?:к|около|примерно|часам к)\\s*([1-9]|1[0-1])(?:\\s|$)");
    private static final Pattern BARE_HOUR_TIME = Pattern.compile("^(?:в\\s+|к\\s+)?([1-9]|1[0-1])$");
    private static final Pattern EXPLICIT_TIME = Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b");
    private static final Pattern SHORT_PARTY_SIZE = Pattern.compile("^(?:на\\s+)?(\\d{1,2})$");
    private static final Pattern TABLE_NUMBER = Pattern.compile("^(?:стол(?:ик)?\\s*)?(1\\d|[1-9])$");
    private static final Pattern TABLE_NUMBER_BEFORE_WORD = Pattern.compile("^(1\\d|[1-9])\\s*стол(?:ик)?$");

    private final IntentExampleRepository intentExampleRepository;
    private final EmbeddingProvider embeddingProvider;
    private final List<RussianNluAdapter> russianNluAdapters;
    private final LlmUnderstandingService llmUnderstandingService;

    public GuestInputUnderstandingService() {
        this(null, null, List.of(), null);
    }

    @Autowired
    public GuestInputUnderstandingService(
            ObjectProvider<IntentExampleRepository> intentExampleRepository,
            ObjectProvider<EmbeddingProvider> embeddingProvider,
            ObjectProvider<RussianNluAdapter> russianNluAdapters,
            ObjectProvider<LlmUnderstandingService> llmUnderstandingService
    ) {
        this(
                intentExampleRepository.getIfAvailable(),
                embeddingProvider.getIfAvailable(),
                russianNluAdapters.orderedStream().toList(),
                llmUnderstandingService.getIfAvailable()
        );
    }

    GuestInputUnderstandingService(
            IntentExampleRepository intentExampleRepository,
            EmbeddingProvider embeddingProvider,
            List<RussianNluAdapter> russianNluAdapters
    ) {
        this(intentExampleRepository, embeddingProvider, russianNluAdapters, null);
    }

    GuestInputUnderstandingService(
            IntentExampleRepository intentExampleRepository,
            EmbeddingProvider embeddingProvider,
            List<RussianNluAdapter> russianNluAdapters,
            LlmUnderstandingService llmUnderstandingService
    ) {
        this.intentExampleRepository = intentExampleRepository;
        this.embeddingProvider = embeddingProvider;
        this.russianNluAdapters = russianNluAdapters == null ? List.of() : List.copyOf(russianNluAdapters);
        this.llmUnderstandingService = llmUnderstandingService;
    }

    public UnderstoodInput understand(String rawText, BotState currentState) {
        return understand(rawText, currentState, Map.of());
    }

    public UnderstoodInput understand(String rawText, BotState currentState, Map<String, Object> context) {
        String raw = rawText == null ? "" : rawText.trim();
        String normalized = normalize(raw);
        Map<String, SlotValue> slots = new LinkedHashMap<>();

        normalized = canonicalMenuPrompt(normalized);
        normalized = normalizeDateShortcut(normalized, slots);
        normalized = applyRussianNlu(normalized, currentState, slots);
        normalized = normalizeTime(normalized, currentState, slots);
        normalized = normalizePartySize(normalized, currentState, slots);
        captureTableSelection(normalized, slots);

        LlmUnderstandingResult llmUnderstanding = understandWithLlm(raw, currentState, normalized, slots, context);
        mergeLlmSlots(slots, llmUnderstanding);

        InputIntent primary = detectIntent(normalized, currentState, slots);
        List<InputIntent> candidates = detectCandidates(normalized, primary, slots);
        double confidence = confidence(primary, slots);
        if (llmUnderstanding.usable(llmUnderstandingService == null ? 1.0 : llmUnderstandingService.minConfidence())) {
            if (llmUnderstanding.intent() != primary) {
                candidates = prependCandidate(candidates, primary);
            }
            primary = llmUnderstanding.intent();
            confidence = Math.max(confidence, llmUnderstanding.confidence());
            candidates = prependCandidate(candidates, primary);
        }
        IntentExampleMatch semanticMatch = findSemanticMatch(normalized, currentState, confidence).orElse(null);
        if (semanticMatch != null) {
            InputIntent matchedIntent = parseIntent(semanticMatch.intent()).orElse(primary);
            if (matchedIntent != primary) {
                candidates = prependCandidate(candidates, primary);
            }
            primary = matchedIntent;
            confidence = Math.max(confidence, Math.min(0.94, semanticMatch.score()));
            candidates = prependCandidate(candidates, matchedIntent);
        }

        return new UnderstoodInput(
                raw,
                normalized,
                primary,
                confidence,
                Map.copyOf(slots),
                List.copyOf(candidates),
                confidence < 0.55,
                confidence < 0.55 ? "Уточните, пожалуйста: бронь, меню, афиша, видео-тур или помощь команды?" : null
        );
    }

    private LlmUnderstandingResult understandWithLlm(
            String raw,
            BotState currentState,
            String normalized,
            Map<String, SlotValue> slots,
            Map<String, Object> context
    ) {
        if (llmUnderstandingService == null) {
            return LlmUnderstandingResult.empty();
        }
        return llmUnderstandingService.understand(raw, currentState, normalized, Map.copyOf(slots), context);
    }

    private void mergeLlmSlots(Map<String, SlotValue> slots, LlmUnderstandingResult llmUnderstanding) {
        if (llmUnderstanding == null || llmUnderstanding.slots() == null || llmUnderstanding.slots().isEmpty()) {
            return;
        }
        for (SlotValue slot : llmUnderstanding.slots()) {
            if (slot == null || slot.name() == null || slot.value() == null || slot.value().isBlank()) {
                continue;
            }
            SlotValue existing = slots.get(slot.name());
            if (existing == null || slot.confidence() > existing.confidence()) {
                slots.put(slot.name(), slot);
            }
        }
    }

    private Optional<IntentExampleMatch> findSemanticMatch(String normalized, BotState currentState, double currentConfidence) {
        if (intentExampleRepository == null || normalized == null || normalized.isBlank() || currentConfidence >= 0.72) {
            return Optional.empty();
        }
        String state = currentState == null ? null : currentState.canonical().name();
        Optional<IntentExampleMatch> lexical = intentExampleRepository.findBestLexicalMatch("AERIS", state, normalized)
                .filter(match -> match.score() >= 0.58);
        if (lexical.isPresent()) {
            return lexical;
        }
        if (embeddingProvider == null) {
            return Optional.empty();
        }
        try {
            List<Double> embedding = embeddingProvider.embed(normalized);
            return intentExampleRepository.findNearestByEmbedding("AERIS", state, embedding, 3).stream()
                    .filter(match -> match.score() >= 0.68)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn(
                    "Semantic intent lookup skipped: state={}, text='{}', reason={}",
                    state,
                    normalized,
                    e.toString()
            );
            return Optional.empty();
        }
    }

    private Optional<InputIntent> parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InputIntent.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private List<InputIntent> prependCandidate(List<InputIntent> candidates, InputIntent intent) {
        List<InputIntent> result = new ArrayList<>();
        result.add(intent);
        for (InputIntent candidate : candidates) {
            if (candidate != intent) {
                result.add(candidate);
            }
        }
        return result;
    }

    private String canonicalMenuPrompt(String normalized) {
        return switch (normalized) {
            case "📅 забронировать стол" -> "забронировать стол";
            case "📖 меню и карты" -> "покажи меню";
            case "🥂 сабраж" -> "сабраж";
            case "🏛 видео-тур" -> "покажи видео-тур";
            case "🎟 афиша" -> "покажи афишу";
            case "✨ концепция" -> "расскажи про концепцию aeris";
            case "🎉 мероприятие" -> "организовать мероприятие";
            case "🛎 помощь команды" -> "помощь команды";
            case "✏️ изменить / отменить" -> "изменить или отменить бронь";
            case "💬 оставить отзыв" -> "оставить отзыв";
            case "💚 чаевые" -> "чаевые";
            case "🤍 донат" -> "донат";
            case "🎨 аукцион" -> "аукцион";
            case "🎁 мерч" -> "мерч";
            case "🏠 главное меню" -> "главное меню";
            default -> normalized;
        };
    }

    private String normalizeDateShortcut(String text, Map<String, SlotValue> slots) {
        if (text.contains("послезавтра")) {
            slots.put("date", new SlotValue("date", "послезавтра", 0.95));
            return text;
        }
        if (text.contains("завтра")) {
            slots.put("date", new SlotValue("date", "завтра", 0.95));
            return text.replace("на завтра", "завтра");
        }
        if (text.contains("сегодня")) {
            slots.put("date", new SlotValue("date", "сегодня", 0.95));
        }
        return text;
    }

    private String applyRussianNlu(String text, BotState currentState, Map<String, SlotValue> slots) {
        String normalized = text;
        for (RussianNluAdapter adapter : russianNluAdapters) {
            RussianNluResult result = adapter.analyze(text, currentState);
            for (RussianNluSlot slot : result.slots()) {
                mergeNluSlot(slots, slot, currentState);
                if ("time".equals(slot.name()) && !normalized.contains(slot.value())) {
                    normalized = normalized + " " + slot.value();
                }
            }
        }
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private void mergeNluSlot(Map<String, SlotValue> slots, RussianNluSlot slot, BotState currentState) {
        if (slot == null || slot.name() == null || slot.value() == null || slot.value().isBlank()) {
            return;
        }
        String name = switch (slot.name()) {
            case "time", "date", "partySize", "tableNumber", "seatingPreference" -> slot.name();
            case "number" -> nluNumberTarget(currentState);
            default -> "";
        };
        if (name.isBlank()) {
            return;
        }
        SlotValue existing = slots.get(name);
        if (existing == null || slot.confidence() > existing.confidence()) {
            slots.put(name, new SlotValue(name, slot.value(), slot.confidence()));
        }
    }

    private String nluNumberTarget(BotState currentState) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE) {
            return "partySize";
        }
        if (state == BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION) {
            return "tableNumber";
        }
        return "";
    }

    private String normalizeTime(String text, BotState currentState, Map<String, SlotValue> slots) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state == BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION) {
            return text;
        }
        text = normalizeTimeWords(text);
        Matcher explicit = EXPLICIT_TIME.matcher(text);
        if (explicit.find()) {
            slots.put("time", new SlotValue("time", explicit.group(), 0.98));
            return text;
        }

        Matcher evening = EVENING_TIME.matcher(text);
        if (evening.find()) {
            int hour = Integer.parseInt(evening.group(1));
            return replaceTime(text, slots, evening, hour + 12);
        }

        Matcher around = AROUND_HOUR_TIME.matcher(text);
        if (around.find()) {
            int hour = Integer.parseInt(around.group(1));
            return replaceTime(text, slots, around, hour + 12);
        }

        Matcher hour = HOUR_TIME.matcher(text);
        if (hour.find()) {
            int parsedHour = Integer.parseInt(hour.group(1));
            return replaceTime(text, slots, hour, parsedHour);
        }
        if (state == BotState.TABLE_BOOKING_COLLECT_TIME) {
            Matcher bare = BARE_HOUR_TIME.matcher(text);
            if (bare.matches()) {
                int parsedHour = Integer.parseInt(bare.group(1));
                return replaceTime(text, slots, bare, parsedHour + 12);
            }
        }
        return text;
    }

    private String normalizeTimeWords(String text) {
        return text
                .replace("одиннадцати", "11")
                .replace("одиннадцать", "11")
                .replace("шести", "6")
                .replace("шесть", "6")
                .replace("восьми", "8")
                .replace("восемь", "8")
                .replace("семи", "7")
                .replace("семь", "7")
                .replace("девяти", "9")
                .replace("девять", "9")
                .replace("десяти", "10")
                .replace("десять", "10");
    }

    private String replaceTime(String text, Map<String, SlotValue> slots, Matcher matcher, int hour) {
        LocalTime time = LocalTime.of(hour, 0);
        String value = "%02d:00".formatted(time.getHour());
        slots.put("time", new SlotValue("time", value, 0.94));
        return matcher.replaceFirst(" " + value + " ").replaceAll("\\s+", " ").trim();
    }

    private String normalizePartySize(String text, BotState currentState, Map<String, SlotValue> slots) {
        Optional<Integer> family = GuestPartyText.adultsWithChildren(text);
        if (family.isPresent()) {
            slots.put("partySize", new SlotValue("partySize", family.get().toString(), 0.96));
            return text;
        }
        if (GuestPartyText.childrenWithoutACount(text)) {
            // The adults alone are not the party: the booking asks how many guests come in all.
            return text;
        }
        Integer partySize = partySizeFromWords(text);
        if (partySize != null) {
            slots.put("partySize", new SlotValue("partySize", partySize.toString(), 0.95));
            return replacePartySizeWords(text, partySize);
        }

        Matcher compact = COMPACT_PARTY_SIZE.matcher(text);
        if (compact.find()) {
            String value = compact.group(1);
            slots.put("partySize", new SlotValue("partySize", value, 0.94));
            return compact.replaceFirst(" на " + value + " гостей ");
        }

        Matcher guests = GUEST_PARTY_SIZE.matcher(text);
        if (guests.find()) {
            slots.put("partySize", new SlotValue("partySize", guests.group(1), 0.94));
        }
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE) {
            Matcher shortParty = SHORT_PARTY_SIZE.matcher(text);
            if (shortParty.matches()) {
                String value = shortParty.group(1);
                slots.put("partySize", new SlotValue("partySize", value, 0.9));
                return "на " + value + " гостей";
            }
        }
        return text;
    }

    private Integer partySizeFromWords(String text) {
        if (containsAny(text, "одного", "один", "одна", "одному", "соло", "я один", "я одна", "буду один", "буду одна", "только я")) {
            return 1;
        }
        if (containsAny(text, "двоих", "двоем", "двоём", "двое", "двух")) {
            return 2;
        }
        if (containsAny(text, "троих", "трое", "трех", "трёх")) {
            return 3;
        }
        if (containsAny(text, "четверых", "четверо", "четырех", "четырёх")) {
            return 4;
        }
        if (containsAny(text, "вдвоем", "вдвоём", "будем вдвоем", "будем вдвоём", "нас двое", "мы вдвоем", "мы вдвоём")) {
            return 2;
        }
        if (containsAny(text, "втроем", "втроём", "нас трое", "мы втроем", "мы втроём")) {
            return 3;
        }
        if (containsAny(text, "вчетвером", "нас четверо", "мы вчетвером")) {
            return 4;
        }
        return null;
    }

    private String replacePartySizeWords(String text, int partySize) {
        String result = text;
        if (partySize == 1) {
            result = replaceVariants(result, "на 1 гостя", "на одного", "одного", "один", "одна", "одному", "соло", "я один", "я одна", "буду один", "буду одна", "только я");
        }
        if (partySize == 2) {
            result = replaceVariants(result, "на 2 гостей", "на двоих", "двоих", "на двоем", "двоем", "на двоём", "двоём", "двое", "двух", "вдвоем", "вдвоём", "будем вдвоем", "будем вдвоём", "нас двое", "мы вдвоем", "мы вдвоём");
        }
        if (partySize == 3) {
            result = replaceVariants(result, "на 3 гостей", "на троих", "троих", "трое", "трех", "трёх", "втроем", "втроём", "нас трое", "мы втроем", "мы втроём");
        }
        if (partySize == 4) {
            result = replaceVariants(result, "на 4 гостей", "на четверых", "четверых", "четверо", "четырех", "четырёх", "вчетвером", "нас четверо", "мы вчетвером");
        }
        return result.replace("гостей гостей", "гостей");
    }

    private String replaceVariants(String text, String replacement, String... variants) {
        String result = text;
        for (String variant : variants) {
            result = result.replace(variant, replacement);
        }
        return result;
    }

    private void captureTableSelection(String text, Map<String, SlotValue> slots) {
        Matcher matcher = TABLE_NUMBER.matcher(text);
        if (matcher.matches()) {
            slots.put("tableNumber", new SlotValue("tableNumber", matcher.group(1), 0.9));
            return;
        }
        Matcher reverseMatcher = TABLE_NUMBER_BEFORE_WORD.matcher(text);
        if (reverseMatcher.matches()) {
            slots.put("tableNumber", new SlotValue("tableNumber", reverseMatcher.group(1), 0.9));
        }
        if (containsAny(text, "винн", "vip", "вип", "бар", "у окна", "окн", "тих", "диван", "не проход", "уют", "выбери сам", "подбери сам", "сам подбери", "любой")) {
            slots.put("seatingPreference", new SlotValue("seatingPreference", text, 0.78));
        }
    }

    private InputIntent detectIntent(String text, BotState currentState, Map<String, SlotValue> slots) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state == BotState.TABLE_BOOKING_COLLECT_DATE && slots.containsKey("date")) {
            return InputIntent.PROVIDE_DATE;
        }
        if (state == BotState.TABLE_BOOKING_COLLECT_TIME && slots.containsKey("time")) {
            return InputIntent.PROVIDE_TIME;
        }
        if (state == BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE && slots.containsKey("partySize")) {
            return InputIntent.PROVIDE_PARTY_SIZE;
        }
        if (state == BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE
                && (slots.containsKey("seatingPreference") || isNoSeatingPreference(text))) {
            return InputIntent.PROVIDE_SEATING_PREFERENCE;
        }
        if (state == BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION
                && (slots.containsKey("tableNumber") || slots.containsKey("seatingPreference") || containsAny(text, "выбери сам", "подбери сам", "сам подбери", "любой"))) {
            return InputIntent.PROVIDE_TABLE_SELECTION;
        }
        if (containsAny(text, "забронировать стол", "забронировать столик", "забронируй стол", "бронь стол", "бронь на", "хочу стол", "хочу столик", "столик", "стол на", "стол завтра", "стол сегодня", "есть места", "нужен стол", "нужен столик", "посадите", "место на")) {
            return InputIntent.TABLE_BOOKING;
        }
        if (looksLikeTableBooking(text, slots)) {
            return InputIntent.TABLE_BOOKING;
        }
        if (containsAny(text, "меню", "винн", "барная карта", "коктей", "поесть")) {
            return InputIntent.MENU_ASSETS;
        }
        if (containsAny(text, "видео-тур", "видеотур", "покажи ресторан", "посмотреть ресторан", "расскажи про ресторан", "расскажи о ресторане", "про ресторан", "о ресторане", "про заведение", "о заведении", "что за ресторан", "что за место", "интерьер", "афиша", "концепц", "шеф")) {
            return InputIntent.QUIET_GUIDE;
        }
        if (containsAny(text, "сабраж", "шампан", "игрист")) {
            return InputIntent.SAFE_PLAY;
        }
        if (containsAny(text, "мероприят", "день рождения", "корпоратив", "банкет", "свадьб")) {
            return InputIntent.EVENT_BOOKING;
        }
        if (containsAny(text, "менеджер", "админ", "помощь команды", "позови человека")) {
            return InputIntent.MANAGER_HELP;
        }
        if (containsAny(text, "отзыв", "жалоб", "похвал")) {
            return InputIntent.FEEDBACK;
        }
        if (containsAny(text, "изменить", "отменить", "перенести")) {
            return InputIntent.CHANGE_CANCEL;
        }
        if (containsAny(text, "чаевые", "на чай", "поблагодарить официанта")) {
            return InputIntent.SMART_TIP;
        }
        if (containsAny(text, "донат", "благотвор", "поддержать проект")) {
            return InputIntent.HIDDEN_HEART;
        }
        if (containsAny(text, "аукцион", "картина", "ставка", "ставлю")) {
            return InputIntent.ART_AUCTION;
        }
        if (containsAny(text, "мерч", "цепь", "купить")) {
            return InputIntent.MERCH;
        }
        if (containsAny(text, "главное меню", "домой", "назад")) {
            return InputIntent.MAIN_MENU;
        }
        if (containsAny(text, "да", "ок", "окей", "подтверждаю")) {
            return InputIntent.AFFIRMATION;
        }
        if (containsAny(text, "нет", "не надо", "отмена")) {
            return InputIntent.NEGATION;
        }
        return InputIntent.UNKNOWN;
    }

    private List<InputIntent> detectCandidates(String text, InputIntent primary, Map<String, SlotValue> slots) {
        List<InputIntent> candidates = new ArrayList<>();
        candidates.add(primary);
        if (primary != InputIntent.TABLE_BOOKING && (slots.containsKey("date") || slots.containsKey("time") || slots.containsKey("partySize"))) {
            candidates.add(InputIntent.TABLE_BOOKING);
        }
        if (primary != InputIntent.MENU_ASSETS && containsAny(text, "меню", "винн", "бар", "коктей")) {
            candidates.add(InputIntent.MENU_ASSETS);
        }
        if (primary != InputIntent.QUIET_GUIDE && containsAny(text, "афиша", "концепц", "видео", "интерьер")) {
            candidates.add(InputIntent.QUIET_GUIDE);
        }
        return candidates;
    }

    private boolean looksLikeTableBooking(String text, Map<String, SlotValue> slots) {
        boolean hasBookingSlot = slots.containsKey("date") || slots.containsKey("time") || slots.containsKey("partySize");
        boolean hasExplicitBookingSignal = containsAny(
                text,
                "заброн", "бронь", "стол", "столик", "посадите", "посадка", "есть места", "нужен стол", "нужен столик"
        );
        return hasBookingSlot && hasExplicitBookingSignal;
    }

    private boolean isNoSeatingPreference(String text) {
        return text.equals("нет")
                || text.equals("не")
                || text.equals("без")
                || text.equals("без пожеланий")
                || text.equals("пожеланий нет")
                || text.equals("нет пожеланий")
                || text.equals("любой")
                || text.equals("любой стол")
                || text.equals("как удобно")
                || text.equals("где удобно")
                || text.equals("на ваше усмотрение")
                || text.equals("на твой выбор")
                || text.equals("выбери сам")
                || text.equals("подбери сам")
                || text.equals("сам подбери");
    }

    private double confidence(InputIntent primary, Map<String, SlotValue> slots) {
        if (primary == InputIntent.UNKNOWN) {
            return 0.25;
        }
        if (!slots.isEmpty()) {
            return 0.92;
        }
        return switch (primary) {
            case TABLE_BOOKING, MENU_ASSETS, QUIET_GUIDE, SAFE_PLAY, EVENT_BOOKING, MANAGER_HELP -> 0.86;
            default -> 0.74;
        };
    }

    private boolean containsAny(String text, String... variants) {
        for (String variant : variants) {
            if (text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }
}
