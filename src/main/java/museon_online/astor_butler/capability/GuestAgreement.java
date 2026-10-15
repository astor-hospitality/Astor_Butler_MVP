package museon_online.astor_butler.capability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The guest's agreement a commitment needs, taken from the turn and never from the model: whether the guest's own words
 * this turn say yes ({@link #saysYes}), and the tables the venue put to the guest in its previous answer
 * ({@code proposed}). A table request runs only for a proposed table after a yes ({@link #covers}); a lunch order only
 * after a yes. Only the Butler's agent builds it, from the guest's words and its own previous turn; a REST caller
 * cannot bring one.
 *
 * <p>The yes is a word check, not a model's reading: any agreement word («да», «ок», «бронируйте»…), no negation and
 * no question. A false «no» costs one more question to the guest; a false «yes» still needs a proposed table.
 */
public record GuestAgreement(boolean yes, List<Table> proposed) {

    /** The reason of the {@code NOT_ALLOWED} a commitment answers without the guest's yes. */
    public static final String NEEDS_GUEST_YES = "needs_guest_yes";
    public static final GuestAgreement NONE = new GuestAgreement(false, List.of());

    /** Reads «9:00» and «09:00», as the tools' arguments do; writes «09:00», as the tools' facts do. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter FACT_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern WORD = Pattern.compile("[^\\p{L}\\p{N}+]+");
    private static final Set<String> AGREE = Set.of("да", "ага", "угу", "ок", "окей", "ok", "okay", "yes", "конечно", "давай",
            "давайте", "бронируй", "бронируйте", "забронируй", "забронируйте", "бронируем", "подтверждаю", "подтверждаем",
            "согласен", "согласна", "согласны", "подходит", "устраивает", "годится", "хорошо", "идет", "договорились", "верно",
            "оформляй", "оформляйте", "оформите", "+");
    private static final Set<String> REFUSE = Set.of("нет", "не", "неа", "ни", "отмена", "отмени", "отмените", "отменить", "стоп",
            "подожди", "подождите", "погоди", "погодите", "передумал", "передумала", "передумали");
    private static final Set<String> AGREE_SIGNS = Set.of("👍", "👌");

    /** A table the venue offered: the day and time in venue time, and how many guests. */
    public record Table(LocalDate date, LocalTime time, int partySize) {

        /** A table from tool facts or arguments ({@code YYYY-MM-DD}, {@code HH:mm}, a whole number); empty when unreadable. */
        public static Optional<Table> of(Object date, Object time, Object partySize) {
            if (date == null || time == null || partySize == null) {
                return Optional.empty();
            }
            try {
                int size = partySize instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                        ? number.intValue() : Integer.parseInt(partySize.toString().trim());
                if (size < 1) {
                    return Optional.empty();
                }
                return Optional.of(new Table(LocalDate.parse(date.toString().trim()),
                        LocalTime.parse(time.toString().trim(), TIME), size));
            } catch (DateTimeParseException | NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    public GuestAgreement {
        proposed = proposed == null ? List.of() : List.copyOf(proposed);
    }

    /** @param guestWords the guest's own words this turn, as the guest wrote them */
    public static GuestAgreement of(String guestWords, List<Table> proposed) {
        return new GuestAgreement(saysYes(guestWords), proposed);
    }

    /** Whether the guest said yes to this very table, offered in the venue's previous answer. */
    public boolean covers(Table table) {
        return yes && proposed.contains(table);
    }

    /** {@code NOT_ALLOWED}: the guest has not said yes to this table; the facts name it, so it can be put to the guest. */
    public static CapabilityResult refusal(Table table) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", NEEDS_GUEST_YES);
        facts.put("date", table.date().toString());
        facts.put("time", table.time().format(FACT_TIME));
        facts.put("partySize", table.partySize());
        return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
    }

    /** An agreement word, no negation, no question. */
    public static boolean saysYes(String words) {
        if (words == null || words.isBlank() || words.contains("?")) {
            return false;
        }
        String text = words.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean agreed = AGREE_SIGNS.stream().anyMatch(text::contains);
        for (String word : WORD.split(text)) {
            if (REFUSE.contains(word)) {
                return false;
            }
            agreed |= AGREE.contains(word);
        }
        return agreed;
    }
}
