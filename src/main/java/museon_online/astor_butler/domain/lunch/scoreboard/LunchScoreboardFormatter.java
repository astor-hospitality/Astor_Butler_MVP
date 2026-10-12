package museon_online.astor_butler.domain.lunch.scoreboard;

import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** «📊 Табло ланча» as the staff chat reads it: short HTML lines, dish titles and wishes escaped, no guest names. */
@Component
public class LunchScoreboardFormatter {

    static final String MEDIA_IDEAS_PLACEHOLDER = "Идеи для СМИ: соберём из отзывов после теста";
    static final int TOP = 3;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.of("ru"));
    private static final Locale RU = Locale.of("ru");

    public String format(LunchScoreboard board) {
        StringBuilder text = new StringBuilder();
        text.append("📊 <b>Табло ланча</b> · ").append(escape(board.date().format(DATE))).append('\n');

        if (board.lunches() == 0 && board.visits() == 0) {
            text.append("\nЛанчей через Butler в этот день не было.\n");
        } else {
            text.append("\nЛанчей: ").append(board.lunches()).append(", гостей: ").append(board.guests()).append('\n');
        }

        if (!board.billsAvailable()) {
            text.append("Счета выключены (ASTOR_BILLING_ENABLED=false): оценок и блюд в табло нет.\n");
        } else if (board.lunches() > 0 || board.visits() > 0) {
            text.append(reviewsLine(board)).append('\n');
        }

        if (!board.dishes().isEmpty()) {
            text.append("\n<b>Хит-парад блюд</b>\n");
            List<LunchScoreboard.Dish> top = board.top(TOP);
            for (int index = 0; index < top.size(); index++) {
                LunchScoreboard.Dish dish = top.get(index);
                text.append(index + 1).append(". ").append(escape(dish.title())).append(" — ")
                        .append(portions(dish.portions())).append(" · ").append(stars(dish)).append('\n');
            }
            board.worst().ifPresent(dish -> text.append("Антирейтинг: ").append(escape(dish.title()))
                    .append(" — ").append(stars(dish)).append('\n'));
        }

        if (board.lunches() > 0) {
            text.append("\nПожелания гостей: ");
            text.append(board.wishes().isEmpty() ? "не было" : escape(String.join("; ", board.wishes())));
            text.append('\n');
        }

        text.append('\n').append(MEDIA_IDEAS_PLACEHOLDER).append('\n');
        text.append("\nТабло по данным Butler. Решения за сменой.");
        return text.toString();
    }

    private static String reviewsLine(LunchScoreboard board) {
        LunchScoreboard.Reviews reviews = board.reviews();
        StringBuilder line = new StringBuilder();
        if (reviews.answered() == 0) {
            line.append("Оценок пока нет");
        } else {
            line.append("Оценок: ").append(reviews.answered()).append(" из ").append(visits(board.visits()))
                    .append(String.format(RU, " · в среднем %.1f", reviews.averageStars()))
                    .append(" · 5★: ").append(reviews.fiveStars()).append(" из ").append(reviews.answered())
                    .append(" (").append(reviews.fiveStarPercent()).append(" %)");
        }
        if (reviews.thumbsUp() + reviews.thumbsDown() > 0) {
            line.append(" · 👍 ").append(reviews.thumbsUp()).append(" / 👎 ").append(reviews.thumbsDown());
        }
        return line.toString();
    }

    private static String stars(LunchScoreboard.Dish dish) {
        if (!dish.rated()) {
            return "без оценок";
        }
        String value = String.format(RU, "★ %.1f", dish.averageStars());
        return dish.fewReviews() ? value + " (мало данных)" : value;
    }

    /** «из 1 визита», «из 5 визитов». */
    private static String visits(int count) {
        return count + (count % 10 == 1 && count % 100 != 11 ? " визита" : " визитов");
    }

    /** «1 порция», «2 порции», «5 порций». */
    static String portions(int count) {
        int mod10 = count % 10, mod100 = count % 100;
        String word = mod10 == 1 && mod100 != 11 ? "порция"
                : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "порции" : "порций";
        return count + " " + word;
    }

    static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
