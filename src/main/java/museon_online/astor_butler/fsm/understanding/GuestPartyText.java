package museon_online.astor_butler.fsm.understanding;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A party a guest describes as adults and children: "двое взрослых и ребёнок" is three guests, not two.
 * It reads only a phrase that counts the adults in so many words. "Нас трое с ребёнком" may mean three or four,
 * so it is left to the ordinary rules.
 */
public final class GuestPartyText {

    private static final int MAX_PARTY = 20;
    private static final String COUNT = "\\d{1,2}|один|одна|одного|одним|одной|двое|два|две|двух|двумя|трое|три|трех|тремя|четверо|четыре|четырех|пятеро|пять|пяти";
    private static final Pattern ADULTS_AND_CHILDREN = Pattern.compile(
            "(?<![\\p{L}\\d])(" + COUNT + ")\\s+взросл\\p{L}*\\s*(?:и|,|\\+|плюс|с)?\\s*(?:(" + COUNT + ")\\s+)?"
                    + "(ребенок|ребенка|ребенком|детей|детьми|дети|малыш\\p{L}*|младен\\p{L}*)(?!\\p{L})");
    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("один", 1), Map.entry("одна", 1), Map.entry("одного", 1), Map.entry("одним", 1), Map.entry("одной", 1),
            Map.entry("двое", 2), Map.entry("два", 2), Map.entry("две", 2), Map.entry("двух", 2), Map.entry("двумя", 2),
            Map.entry("трое", 3), Map.entry("три", 3), Map.entry("трех", 3), Map.entry("тремя", 3),
            Map.entry("четверо", 4), Map.entry("четыре", 4), Map.entry("четырех", 4),
            Map.entry("пятеро", 5), Map.entry("пять", 5), Map.entry("пяти", 5));

    private GuestPartyText() {
    }

    /** Adults plus children, when the guest counted both. */
    public static Optional<Integer> adultsWithChildren(String text) {
        Matcher matcher = ADULTS_AND_CHILDREN.matcher(normalize(text));
        if (!matcher.find()) {
            return Optional.empty();
        }
        Integer children = matcher.group(2) != null ? number(matcher.group(2)) : oneChild(matcher.group(3)) ? Integer.valueOf(1) : null;
        Integer adults = number(matcher.group(1));
        if (adults == null || children == null) {
            return Optional.empty();
        }
        int total = adults + children;
        return total >= 1 && total <= MAX_PARTY ? Optional.of(total) : Optional.empty();
    }

    /** The guest counted the adults and mentioned children without saying how many: the party size is still unknown. */
    public static boolean childrenWithoutACount(String text) {
        Matcher matcher = ADULTS_AND_CHILDREN.matcher(normalize(text));
        return matcher.find() && matcher.group(2) == null && !oneChild(matcher.group(3));
    }

    private static boolean oneChild(String noun) {
        return noun.startsWith("ребен") || noun.equals("малыш") || noun.equals("малыша") || noun.equals("малышом")
                || noun.equals("младенец") || noun.equals("младенца") || noun.equals("младенцем");
    }

    private static Integer number(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Integer.valueOf(value);
        }
        return WORDS.get(value);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.forLanguageTag("ru")).replace('ё', 'е');
    }
}
