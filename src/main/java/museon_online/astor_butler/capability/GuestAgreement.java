package museon_online.astor_butler.capability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The guest's agreement a commitment needs, taken from the turn and never from the model: whether the guest's own words
 * this turn say yes ({@link #saysYes}) or ask to cancel ({@link #asksToCancel}), and what the venue put to the guest in
 * its previous answer: the tables ({@code proposed}), the reservations offered to cancel ({@code cancellations}) and
 * the changes offered ({@code changes}). A table request runs only for a proposed table after a yes ({@link #covers});
 * a change only for that very offered change after a yes ({@link #allowsChange}); a lunch order only after a yes; a
 * cancellation only on the guest's own request or a yes to the offered one ({@link #allowsCancel}). Only the Butler's
 * agent builds it, from the guest's words and its own previous turn; a REST caller cannot bring one.
 *
 * <p>The yes is a word check, not a model's reading: any agreement word («да», «ок», «бронируйте»…), no negation and
 * no question. A false «no» costs one more question to the guest; a false «yes» still needs a proposed table.
 */
public record GuestAgreement(boolean yes, List<Table> proposed, boolean cancels, List<Long> cancellations,
                             List<Change> changes, boolean cancelsBill) {

    /** The reason of the {@code NOT_ALLOWED} a commitment answers without the guest's yes. */
    public static final String NEEDS_GUEST_YES = "needs_guest_yes";
    public static final GuestAgreement NONE = new GuestAgreement(false, List.of());
    /** What a refused change or cancellation would have done, in its {@code action} fact. */
    public static final String CHANGE = "change";
    public static final String CANCEL = "cancel";

    /** Reads «9:00» and «09:00», as the tools' arguments do; writes «09:00», as the tools' facts do. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter FACT_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FACT_DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final String[] WEEKDAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    private static final Pattern WORD = Pattern.compile("[^\\p{L}\\p{N}+]+");
    private static final Set<String> AGREE = Set.of("да", "ага", "угу", "ок", "окей", "ok", "okay", "yes", "конечно", "давай",
            "давайте", "бронируй", "бронируйте", "забронируй", "забронируйте", "бронируем", "подтверждаю", "подтверждаем",
            "согласен", "согласна", "согласны", "подходит", "устраивает", "годится", "хорошо", "идет", "договорились", "верно",
            "оформляй", "оформляйте", "оформите", "+");
    private static final Set<String> REFUSE = Set.of("нет", "не", "неа", "ни", "отмена", "отмени", "отмените", "отменить", "стоп",
            "подожди", "подождите", "погоди", "погодите", "передумал", "передумала", "передумали");
    private static final Set<String> AGREE_SIGNS = Set.of("👍", "👌");
    /** «Отмените», «отменить бронь»: the refusal words that cancel, and their kin. */
    private static final Set<String> CANCEL_WORDS = Set.of("отмени", "отмените", "отменить", "отменять", "отменяй", "отменяйте",
            "отменим", "отменяем");
    /** «Отмена» alone stops the last offer; «отмена брони», «сделайте отмену брони» ask to cancel. */
    private static final Set<String> CANCEL_NOUNS = Set.of("отмена", "отмену");
    private static final Set<String> BOOKING_WORDS = Set.of("бронь", "брони", "бронирования", "заказ", "заказа", "заявки", "стола");
    /** The bill, as guests name it: «отмените счёт» cancels the bill, never the booking. */
    private static final Set<String> BILL_WORDS = Set.of("счет", "счета", "чек", "чека");
    /** The booking itself, named: with one of these «отмените счёт и бронь» still asks to cancel the booking. */
    private static final Set<String> RESERVATION_WORDS = Set.of("бронь", "брони", "бронирование", "бронирования", "стол", "стола", "столик", "визит");
    /** After «не»: the guest will not come («не придём»). */
    private static final Set<String> NOT_COMING = Set.of("придем", "приду", "придут", "придет", "приедем", "приеду", "приедут");
    /** After «не»: the guest cannot, when nothing or «прийти» follows («не сможем», «не получится прийти»). */
    private static final Set<String> CANNOT = Set.of("сможем", "смогу", "сможет", "смогут", "получится", "успеем");
    private static final Set<String> COME = Set.of("прийти", "придти", "приехать", "быть");
    /** A guest who names a time or moves the visit («не успеем к 19», «лучше позже») is not cancelling it. */
    private static final List<String> MOVE_STEMS = List.of("перенес", "перенос", "передвин", "сдвин", "помен", "измен", "лучше",
            "позже", "раньше", "вместо");

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

    /**
     * A change the venue offered: the reservation and what it would set (null stays as it was). Its facts are those of
     * the {@code needs_guest_yes} refusal, so the offer is read back from them exactly.
     */
    public record Change(long reservationId, LocalDate date, LocalTime time, Integer partySize) {

        /** The change from a refusal's facts; empty when unreadable. Numbers may come back from JSON as any kind. */
        public static Optional<Change> of(Map<String, Object> facts) {
            if (facts == null || !(facts.get("reservationId") instanceof Number id)) {
                return Optional.empty();
            }
            try {
                Object date = facts.get("date");
                Object time = facts.get("time");
                Object size = facts.get("partySize");
                return Optional.of(new Change(id.longValue(),
                        date == null ? null : LocalDate.parse(date.toString().trim()),
                        time == null ? null : LocalTime.parse(time.toString().trim(), TIME),
                        size == null ? null : size instanceof Number number ? Integer.valueOf(number.intValue())
                                : Integer.valueOf(size.toString().trim())));
            } catch (DateTimeParseException | NumberFormatException e) {
                return Optional.empty();
            }
        }

        /** What the change would set, as the facts write it. */
        public Map<String, Object> facts() {
            Map<String, Object> facts = new LinkedHashMap<>();
            if (date != null) {
                facts.put("date", date.toString());
                facts.put("dateLabel", dateLabel(date));
            }
            if (time != null) {
                facts.put("time", time.format(FACT_TIME));
            }
            if (partySize != null) {
                facts.put("partySize", partySize);
            }
            return facts;
        }
    }

    public GuestAgreement {
        proposed = proposed == null ? List.of() : List.copyOf(proposed);
        cancellations = cancellations == null ? List.of() : List.copyOf(cancellations);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** An agreement without the guest's own request to cancel the bill. */
    public GuestAgreement(boolean yes, List<Table> proposed, boolean cancels, List<Long> cancellations, List<Change> changes) {
        this(yes, proposed, cancels, cancellations, changes, false);
    }

    /** Also how JSON reads one: a REST context may carry an agreement, which the controller drops anyway. */
    @JsonCreator
    public GuestAgreement(@JsonProperty("yes") boolean yes, @JsonProperty("proposed") List<Table> proposed) {
        this(yes, proposed, false, List.of(), List.of(), false);
    }

    /** @param guestWords the guest's own words this turn, as the guest wrote them */
    public static GuestAgreement of(String guestWords, List<Table> proposed) {
        return of(guestWords, proposed, List.of(), List.of());
    }

    /**
     * @param cancellations the reservations the venue's previous answer offered to cancel
     * @param changes the changes it offered
     */
    public static GuestAgreement of(String guestWords, List<Table> proposed, Collection<Long> cancellations,
                                    Collection<Change> changes) {
        return new GuestAgreement(saysYes(guestWords), proposed, asksToCancel(guestWords),
                cancellations == null ? List.of() : List.copyOf(cancellations),
                changes == null ? List.of() : List.copyOf(changes), asksToCancelBill(guestWords));
    }

    /** Whether the guest said yes to this very change, offered in the venue's previous answer. */
    public boolean allowsChange(Change change) {
        return yes && changes.contains(change);
    }

    /** Whether the guest said yes to this very table, offered in the venue's previous answer. */
    public boolean covers(Table table) {
        return yes && proposed.contains(table);
    }

    /**
     * The guest's own request to cancel the bill, as words: a cancel word («отмените», «отменить», «отмена») not under
     * «не», and the bill named («счёт», «чек»). A question asks, it does not request; there is no «yes» to an offer here,
     * since the venue never offers to cancel a bill.
     */
    public static boolean asksToCancelBill(String words) {
        if (words == null || words.isBlank() || words.contains("?")) {
            return false;
        }
        String text = words.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<String> said = Arrays.stream(WORD.split(text)).filter(word -> !word.isEmpty()).toList();
        if (said.stream().noneMatch(BILL_WORDS::contains)) {
            return false;
        }
        for (int i = 0; i < said.size(); i++) {
            String word = said.get(i);
            boolean negated = i > 0 && "не".equals(said.get(i - 1)) || i > 1 && "не".equals(said.get(i - 2));
            if ((CANCEL_WORDS.contains(word) || CANCEL_NOUNS.contains(word)) && !negated) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesTheBillOnly(List<String> said) {
        return said.stream().anyMatch(BILL_WORDS::contains) && said.stream().noneMatch(RESERVATION_WORDS::contains);
    }

    /** Whether this reservation may be cancelled: the guest asked to cancel this turn, or said yes to this very offer. */
    public boolean allowsCancel(long reservationId) {
        return cancels || yes && cancellations.contains(reservationId);
    }

    /** {@code NOT_ALLOWED}: the guest has not said yes to this table; the facts name it, so it can be put to the guest. */
    public static CapabilityResult refusal(Table table) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", NEEDS_GUEST_YES);
        facts.put("date", table.date().toString());
        facts.put("dateLabel", dateLabel(table.date()));
        facts.put("time", table.time().format(FACT_TIME));
        facts.put("partySize", table.partySize());
        return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
    }

    /**
     * {@code NOT_ALLOWED}: the guest has not agreed to change or cancel this reservation. {@code action} is
     * {@value #CHANGE} or {@value #CANCEL}; {@code asked} is what the change would set, so it can be put to the guest.
     */
    public static CapabilityResult refusal(long reservationId, String action, Map<String, Object> asked) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", NEEDS_GUEST_YES);
        facts.put("reservationId", reservationId);
        facts.put("action", action);
        if (asked != null) {
            facts.putAll(asked);
        }
        return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
    }

    /** {@code HH:mm}, as the facts write a time. */
    public static String factTime(LocalTime time) {
        return time.format(FACT_TIME);
    }

    /** «сб 17.10»: the weekday computed here, for the model to copy rather than work out (it gets them wrong). */
    public static String dateLabel(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() - 1] + " " + date.format(FACT_DAY);
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

    /**
     * The guest's own request to cancel, as words, like the yes: a cancel word or «отмена брони» not under «не» (one or
     * two words before: «отмените бронь», but not «не надо отменять» or «это не отмена брони»), or «не придём» /
     * «не сможем (прийти)» with no time named and no move. A question («отменить?») asks, it does not request. A false
     * «no» costs one question; the model then asks.
     */
    public static boolean asksToCancel(String words) {
        if (words == null || words.isBlank() || words.contains("?")) {
            return false;
        }
        String text = words.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<String> said = Arrays.stream(WORD.split(text)).filter(word -> !word.isEmpty()).toList();
        if (namesTheBillOnly(said)) {
            // «Отмените счёт» is about the bill (bill_cancel), not the booking.
            return false;
        }
        boolean moves = text.chars().anyMatch(Character::isDigit)
                || said.stream().anyMatch(word -> MOVE_STEMS.stream().anyMatch(word::startsWith));
        for (int i = 0; i < said.size(); i++) {
            String word = said.get(i);
            boolean afterNot = i > 0 && "не".equals(said.get(i - 1));
            boolean negated = afterNot || i > 1 && "не".equals(said.get(i - 2));
            if (CANCEL_WORDS.contains(word)) {
                if (!negated) {
                    return true;
                }
            } else if (CANCEL_NOUNS.contains(word)) {
                if (!negated && i + 1 < said.size() && BOOKING_WORDS.contains(said.get(i + 1))) {
                    return true;
                }
            } else if (afterNot && !moves && (NOT_COMING.contains(word)
                    || CANNOT.contains(word) && (i + 1 == said.size() || COME.contains(said.get(i + 1))))) {
                return true;
            }
        }
        return false;
    }
}
