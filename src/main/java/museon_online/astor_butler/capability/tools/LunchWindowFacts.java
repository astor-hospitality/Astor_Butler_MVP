package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * When the business lunch is served, as the agents' tools tell it: the days ({@code days}), the hours ({@code from},
 * {@code to}) and whether it is served on the asked day ({@code served}, by {@link BusinessLunchService#windowIssue}, the
 * rule the bot's own lunch dialogue uses). When it is not, the nearest day it is ({@code nextServedDate},
 * {@code nextServedDay} in words), so that on a Friday «завтра» the agent offers Monday instead of a Saturday lunch.
 */
final class LunchWindowFacts {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** «понедельник, 19 октября»: the model names the day as given and never works the weekday out itself. */
    private static final DateTimeFormatter DAY_WORDS = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU);

    private LunchWindowFacts() {
    }

    /**
     * {@code served}, {@code days}, {@code from}, {@code to}; with {@code served=false} also the nearest day the lunch is
     * served on, from the asked day on (the same day when only the hours were wrong).
     */
    static Map<String, Object> of(BusinessLunchOffer offer, LocalDate date, boolean served, LocalDate today, LocalTime now) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("served", served);
        facts.put("days", offer.days().stream().map(Enum::name).toList());
        facts.put("from", offer.from().format(TIME));
        facts.put("to", offer.to().format(TIME));
        if (!served) {
            BusinessLunchService.nextLunchDay(offer, date, today, now).ifPresent(next -> {
                facts.put("nextServedDate", next.toString());
                facts.put("nextServedDay", next.format(DAY_WORDS));
            });
        }
        return facts;
    }
}
