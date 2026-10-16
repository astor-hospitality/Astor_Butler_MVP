package museon_online.astor_butler.agent;

import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guest never reads an internal lunch dish code (live test 16.10: «Напишите коды блюд… SALAD_COLESLAW»). Whatever the
 * model wrote, a code in the reply becomes the dish's title from the menu; a code in brackets next to the title
 * («Салат коул слоу (SALAD_COLESLAW)») is dropped. A code with «_» is matched in any case; a one-word code («ESPRESSO»)
 * only in capitals, so an ordinary word is never touched.
 */
final class LunchDishNames {

    private static final String EDGE_BEFORE = "(?<![\\p{L}\\p{N}_])";
    private static final String EDGE_AFTER = "(?![\\p{L}\\p{N}_])";

    private LunchDishNames() {
    }

    static String replaceCodes(String text, BusinessLunchOffer offer) {
        if (text == null || text.isBlank() || offer == null) {
            return text;
        }
        List<BusinessLunchOffer.Dish> dishes = new ArrayList<>();
        for (BusinessLunchOffer.Course course : offer.courses()) {
            dishes.addAll(course.dishes());
        }
        // The longer code first: SALAD_HERRING_APPLE before a shorter code it might contain.
        dishes.sort(Comparator.comparingInt((BusinessLunchOffer.Dish dish) -> dish.code().length()).reversed());
        String result = text;
        for (BusinessLunchOffer.Dish dish : dishes) {
            String code = dish.code();
            if (code == null || code.isBlank() || dish.title() == null) {
                continue;
            }
            int flags = code.contains("_") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            String quoted = Pattern.quote(code);
            // «Title (CODE)», «Title – CODE», «Title: `CODE`»: the title is already there, the code goes.
            Pattern besideTitle = Pattern.compile(Pattern.quote(dish.title())
                    + "(\\s*\\(\\s*`?" + quoted + "`?\\s*\\)|\\s*[–—:-]\\s*`?" + quoted + "`?" + EDGE_AFTER + ")", flags);
            result = besideTitle.matcher(result).replaceAll(Matcher.quoteReplacement(dish.title()));
            // «(CODE)» after some other words: the brackets go with it.
            Pattern bracketed = Pattern.compile("\\s*\\(\\s*`?" + quoted + "`?\\s*\\)", flags);
            result = bracketed.matcher(result).replaceAll(Matcher.quoteReplacement(" (" + dish.title() + ")"));
            Pattern bare = Pattern.compile("`?" + EDGE_BEFORE + quoted + EDGE_AFTER + "`?", flags);
            result = bare.matcher(result).replaceAll(Matcher.quoteReplacement(dish.title()));
        }
        return result;
    }
}
