package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A dish code as the model wrote it, read against the lunch menu. The exact code wins; otherwise the one dish whose code
 * or title the words clearly name («COLSLAW» → SALAD_COLESLAW, «PEAS_SOUP» → PEA_SOUP_SMOKED, «паста болоньезе»).
 * Two dishes that fit equally well, or none, give nothing: the model gets the menu codes back instead of a guess.
 */
final class DishCodes {

    private DishCodes() {
    }

    static Optional<String> resolve(BusinessLunchOffer offer, String asked) {
        if (asked == null || asked.isBlank()) {
            return Optional.empty();
        }
        if (offer.dish(asked.trim()).isPresent()) {
            return Optional.of(asked.trim());
        }
        List<String> words = words(asked);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        String best = null;
        double bestScore = 0;
        boolean tie = false;
        for (BusinessLunchOffer.Course course : offer.courses()) {
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                List<String> names = new ArrayList<>(words(dish.code()));
                names.addAll(words(dish.title()));
                double score = score(words, names);
                if (score > bestScore) {
                    best = dish.code();
                    bestScore = score;
                    tie = false;
                } else if (score == bestScore && score > 0 && !dish.code().equals(best)) {
                    tie = true;
                }
            }
        }
        // Every word the model wrote must name the dish; one stray word means it is not clearly this one.
        return best != null && !tie && bestScore >= words.size() ? Optional.of(best) : Optional.empty();
    }

    /** The menu as codes and titles, for the model to choose again. */
    static List<Map<String, Object>> menu(BusinessLunchOffer offer) {
        List<Map<String, Object>> menu = new ArrayList<>();
        for (BusinessLunchOffer.Course course : offer.courses()) {
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", dish.code());
                item.put("title", dish.title());
                menu.add(item);
            }
        }
        return menu;
    }

    /** How many of the asked words some name word matches. */
    private static double score(List<String> asked, List<String> names) {
        double score = 0;
        for (String word : asked) {
            for (String name : names) {
                if (same(word, name)) {
                    score += 1;
                    break;
                }
            }
        }
        return score;
    }

    /**
     * One word for another: equal, one a stem of the other (four letters at least, or one letter more: «peas» — «pea»),
     * or one letter apart («colslaw» — «coleslaw»).
     */
    static boolean same(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int shorter = Math.min(a.length(), b.length());
        boolean stem = a.startsWith(b) || b.startsWith(a);
        if (stem && (shorter >= 4 || Math.abs(a.length() - b.length()) <= 1)) {
            return true;
        }
        return shorter >= 4 && Math.abs(a.length() - b.length()) <= 1 && distance(a, b) <= 1;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String word : text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
