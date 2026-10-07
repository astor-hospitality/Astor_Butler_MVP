package museon_online.astor_butler.fsm.understanding;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sum of money the way a guest writes it: "500", "1 500 рублей", "2к", "1,5 тыс", "сто рублей", "полторы тысячи".
 * Whole rubles only, kopecks are dropped. A minus or a zero is told apart from a text with no sum in it,
 * because the guest deserves a different answer to each.
 */
public final class GuestMoneyText {

    /** The largest sum that is read. A longer number is far more likely a slip of the finger than a sum. */
    public static final long MAX_RUBLES = 9_999_999L;

    public enum Kind {
        AMOUNT,
        NOT_POSITIVE,
        NONE
    }

    /** @param rubles the sum when {@code kind} is {@code AMOUNT}, otherwise zero */
    public record Reading(Kind kind, long rubles) {

        private static final Reading NONE = new Reading(Kind.NONE, 0);
        private static final Reading NOT_POSITIVE = new Reading(Kind.NOT_POSITIVE, 0);

        public boolean isAmount() {
            return kind == Kind.AMOUNT;
        }
    }

    private static final Pattern NUMBER = Pattern.compile(
            // Not in the middle of a word or of another number, so "1.50" is not read as 1 and then 50.
            "(?<![\\p{L}\\d.,])"
                    // 1: a minus written right before the digits. A dash with a space after it is punctuation: "чаевые - 500".
                    + "(?:([-−–])|(минус)\\s+)?"
                    // 3: the digits; "1 500" and "50 000" are one number each.
                    + "(\\d{1,3}(?:[ \\u00A0]\\d{3})+|\\d+)"
                    // 4: a fraction, kept only for thousands: "1,5 тыс".
                    + "(?:[.,](\\d{1,2}))?"
                    // 5, 6: thousands, "2к" or "2 тыс".
                    + "(?:([кk])(?![\\p{L}\\d])|\\s*(тыс\\p{L}*|тыщ\\p{L}*))?"
                    // 7: the currency.
                    + "(?:\\s*(руб\\p{L}*|₽|р\\.?(?![\\p{L}\\d])))?");

    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("один", 1), Map.entry("одна", 1), Map.entry("одну", 1), Map.entry("два", 2), Map.entry("две", 2),
            Map.entry("три", 3), Map.entry("четыре", 4), Map.entry("пять", 5), Map.entry("шесть", 6), Map.entry("семь", 7),
            Map.entry("восемь", 8), Map.entry("девять", 9), Map.entry("десять", 10), Map.entry("одиннадцать", 11),
            Map.entry("двенадцать", 12), Map.entry("тринадцать", 13), Map.entry("четырнадцать", 14), Map.entry("пятнадцать", 15),
            Map.entry("шестнадцать", 16), Map.entry("семнадцать", 17), Map.entry("восемнадцать", 18), Map.entry("девятнадцать", 19),
            Map.entry("двадцать", 20), Map.entry("тридцать", 30), Map.entry("сорок", 40), Map.entry("пятьдесят", 50),
            Map.entry("шестьдесят", 60), Map.entry("семьдесят", 70), Map.entry("восемьдесят", 80), Map.entry("девяносто", 90),
            Map.entry("сто", 100), Map.entry("двести", 200), Map.entry("триста", 300), Map.entry("четыреста", 400),
            Map.entry("пятьсот", 500), Map.entry("шестьсот", 600), Map.entry("семьсот", 700), Map.entry("восемьсот", 800),
            Map.entry("девятьсот", 900));
    private static final Set<String> THOUSAND = Set.of("тысяча", "тысячи", "тысячу", "тысяч", "тыща", "тыщи", "тыщу", "тыщ", "тыс");
    private static final Set<String> ONE_AND_A_HALF = Set.of("полторы", "полтора");
    private static final Set<String> NOTHING = Set.of("ноль", "нисколько");

    private GuestMoneyText() {
    }

    public static Reading read(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        Reading digits = fromDigits(normalized);
        return digits.kind() == Kind.NONE ? fromWords(normalized) : digits;
    }

    /** The number that carries a currency or thousands wins; otherwise the first number that can be a sum. */
    private static Reading fromDigits(String text) {
        Reading first = Reading.NONE;
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            boolean thousands = matcher.group(5) != null || matcher.group(6) != null;
            boolean marked = thousands || matcher.group(7) != null;
            String digits = matcher.group(3).replaceAll("\\D", "");
            if (digits.length() > 9) {
                continue;
            }
            long rubles = Long.parseLong(digits);
            if (thousands) {
                String fraction = matcher.group(4) == null ? "" : matcher.group(4);
                rubles = rubles * 1000 + Long.parseLong((fraction + "000").substring(0, 3));
            }
            // A lone digit with nothing around it is more often a table or a count than a sum.
            if (!marked && rubles > 0 && rubles < 10 || rubles > MAX_RUBLES) {
                continue;
            }
            boolean minus = matcher.group(1) != null || matcher.group(2) != null;
            Reading reading = minus || rubles == 0 ? Reading.NOT_POSITIVE : new Reading(Kind.AMOUNT, rubles);
            if (marked) {
                return reading;
            }
            if (first.kind() == Kind.NONE) {
                first = reading;
            }
        }
        return first;
    }

    /** "сто пятьдесят", "две тысячи пятьсот рублей", "полторы тысячи". The sum ends at the first word that is not part of it. */
    private static Reading fromWords(String text) {
        long total = 0;
        long current = 0;
        boolean started = false;
        boolean minus = false;
        boolean nothing = false;
        boolean oneAndAHalf = false;
        boolean marked = false;
        for (String token : text.split("[^\\p{L}₽]+")) {
            Integer value = WORDS.get(token);
            if (value != null) {
                current += value;
                started = true;
            } else if (NOTHING.contains(token)) {
                nothing = true;
                started = true;
            } else if (ONE_AND_A_HALF.contains(token)) {
                oneAndAHalf = true;
                started = true;
            } else if (THOUSAND.contains(token) || token.equals("полтысячи")) {
                total += token.equals("полтысячи") ? 500 : oneAndAHalf ? 1500 : Math.max(current, 1) * 1000L;
                current = 0;
                oneAndAHalf = false;
                started = true;
                marked = true;
            } else if (!started) {
                minus = token.equals("минус");
            } else {
                marked = marked || token.startsWith("руб") || token.equals("р") || token.equals("₽");
                break;
            }
        }
        long rubles = total + current;
        // "Полторы" without "тысячи" is not a sum, and neither is "один момент" or "три раза спасибо".
        if (!started || oneAndAHalf || rubles > MAX_RUBLES || rubles > 0 && rubles < 10 && !marked) {
            return Reading.NONE;
        }
        if (rubles == 0) {
            return nothing ? Reading.NOT_POSITIVE : Reading.NONE;
        }
        return minus ? Reading.NOT_POSITIVE : new Reading(Kind.AMOUNT, rubles);
    }
}
