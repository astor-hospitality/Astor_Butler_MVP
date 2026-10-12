package museon_online.astor_butler.api.glasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The short order a waiter says at the table (#184): «забить 14 на 12» — dish number 14 to table 12. Deterministic,
 * no model: the phrase either matches one of a few fixed shapes or it is not an order and goes to assist as before.
 *
 * Shapes (case does not matter, punctuation between the parts is ignored):
 * <ul>
 *   <li>«забить|пробить|заказ 14 на 12», «забить 14 и 7 на 12», «пробить 14, 7 на 12»</li>
 *   <li>«14 на стол 12», «14 и 7 на 12» (the word «стол» before the table is optional)</li>
 *   <li>«стол 12: 14, 7», «стол 12 — 14 и 7»</li>
 * </ul>
 * A quantity follows the dish number: «14 два раза», «14 ×2», «14 x2», «14 2 раза» — words один…пять or a digit
 * 1–9. Without one the dish is ordered once. Any other quantity, a missing table or a stray word makes the phrase
 * not an order: a wrong guess at the table is worse than an informational answer.
 */
final class GlassesShortOrderGrammar {
    /** One dish of the order, as spoken: its number and how many times. */
    record Item(int number, int quantity) { }

    /** The order as spoken: the table code and the dishes in the order they were named. */
    record Order(String tableCode, List<Item> items) { }

    static final int MAX_ITEMS = 6;
    static final int MAX_QUANTITY = 9;
    private static final Map<String, Integer> WORDS = Map.of("один", 1, "одну", 1, "одна", 1,
            "два", 2, "две", 2, "три", 3, "четыре", 4, "пять", 5);
    // «×2», «x2», «два раза», «две», «2 раза» — or nothing, which is once.
    private static final String QUANTITY = "(?:\\s*[x×х]\\s*(?<qx>[1-9])"
            + "|\\s+(?<qw>один|одну|одна|два|две|три|четыре|пять)(?:\\s+раз[а]?)?"
            + "|\\s+(?<qd>[1-9])\\s+раз[а]?)?";
    // One dish after an optional separator; the number ends at a space, a comma or the end, never inside «1234».
    private static final Pattern ITEM = Pattern.compile("\\G[\\s,;]*(?:и\\s+)?(?<n>\\d{1,3})" + QUANTITY + "(?=[\\s,;]|$)");
    private static final String TABLE = "(?<table>\\d{1,3}[а-я]?)(?!\\d)";
    private static final Pattern VERB_FIRST = Pattern.compile("^(?:забить|забей|пробить|пробей|заказ)\\s*[:,\\-—–]?\\s*"
            + "(?<items>.+?)\\s+на\\s+(?:стол\\s+)?" + TABLE + "$");
    private static final Pattern ITEMS_FIRST = Pattern.compile("^(?<items>.+?)\\s+на\\s+(?:стол\\s+)?" + TABLE + "$");
    private static final Pattern TABLE_FIRST = Pattern.compile("^стол\\s+" + TABLE + "\\s*[:,\\-—–]?\\s*(?<items>.+)$");

    private GlassesShortOrderGrammar() { }

    /** The order the words describe, or null when they are not a short order. */
    static Order parse(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return null;
        for (Pattern shape : new Pattern[]{VERB_FIRST, TABLE_FIRST, ITEMS_FIRST}) {
            Matcher m = shape.matcher(normalized);
            if (!m.matches()) continue;
            List<Item> items = items(m.group("items"));
            if (items == null) continue;
            return new Order(m.group("table"), items);
        }
        return null;
    }

    /** «14, 7 два раза и 3 ×2» → the items, or null when anything in between is not a dish with an optional quantity. */
    private static List<Item> items(String spoken) {
        List<Item> items = new ArrayList<>();
        Matcher m = ITEM.matcher(spoken);
        int end = 0;
        while (m.find()) {
            Item item = item(m);
            if (item == null) return null;
            items.add(item);
            if (items.size() > MAX_ITEMS) return null;
            end = m.end();
        }
        if (items.isEmpty() || end != spoken.length()) return null;
        return List.copyOf(items);
    }

    private static Item item(Matcher m) {
        int number = Integer.parseInt(m.group("n"));
        if (number <= 0) return null;
        int quantity = 1;
        if (m.group("qx") != null) quantity = Integer.parseInt(m.group("qx"));
        else if (m.group("qd") != null) quantity = Integer.parseInt(m.group("qd"));
        else if (m.group("qw") != null) quantity = WORDS.getOrDefault(m.group("qw"), 0);
        if (quantity < 1 || quantity > MAX_QUANTITY) return null;
        return new Item(number, quantity);
    }

    /** Lower case, «ё» as «е», surrounding quotes and end punctuation dropped, whitespace collapsed. */
    private static String normalize(String text) {
        if (text == null) return "";
        String s = text.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = s.replaceAll("^[\\s«\"'„“(\\[]+", "").replaceAll("[\\s.!?…»\"')\\]]+$", "");
        return s.replaceAll("\\s+", " ");
    }
}
