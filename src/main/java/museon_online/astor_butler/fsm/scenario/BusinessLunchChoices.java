package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The days and times a business lunch can be had, as buttons and as words. The lunch dialogue and the change of an
 * already placed lunch both use it, so a guest hears the same hours and the same refusal in both.
 */
final class BusinessLunchChoices {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DAY_BUTTON = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
    private static final int DAY_CHOICES = 5;
    private static final int LOOK_AHEAD_DAYS = 21;

    private BusinessLunchChoices() {
    }

    /** Why this day cannot be a lunch day; a day whose lunch hours have all passed counts as passed. */
    static Optional<BusinessLunchService.WindowIssue> dayIssue(BusinessLunchOffer offer, BusinessLunchService service, LocalDate day) {
        Optional<BusinessLunchService.WindowIssue> issue = service.windowIssue(offer, day, null);
        return issue.isPresent() || !times(offer, service, day).isEmpty() ? issue : Optional.of(BusinessLunchService.WindowIssue.ALREADY_PASSED);
    }

    /** The next lunch days as button labels: "Сегодня 05.10", "Завтра 06.10", "Ср 07.10". */
    static List<String> days(BusinessLunchOffer offer, BusinessLunchService service, LocalDate today) {
        List<String> labels = new ArrayList<>();
        for (int ahead = 0; ahead < LOOK_AHEAD_DAYS && labels.size() < DAY_CHOICES; ahead++) {
            LocalDate day = today.plusDays(ahead);
            if (dayIssue(offer, service, day).isPresent()) {
                continue;
            }
            String name = ahead == 0 ? "Сегодня" : ahead == 1 ? "Завтра" : capitalize(day.getDayOfWeek().getDisplayName(TextStyle.SHORT, RU));
            labels.add(name + " " + day.format(DAY_BUTTON));
        }
        return labels;
    }

    /** Half-hour starts inside the lunch hours; for today only the ones still ahead. */
    static List<String> times(BusinessLunchOffer offer, BusinessLunchService service, LocalDate day) {
        List<String> labels = new ArrayList<>();
        for (LocalTime time = offer.from(); time.isBefore(offer.to()); time = time.plusMinutes(30)) {
            if (service.windowIssue(offer, day, time).isEmpty()) {
                labels.add(time.format(TIME_TEXT));
            }
            if (time.plusMinutes(30).isBefore(time)) {
                break;
            }
        }
        return labels;
    }

    static String explain(BusinessLunchOffer offer, BusinessLunchService.WindowIssue issue) {
        return switch (issue) {
            case NOT_A_LUNCH_DAY -> "Бизнес-ланч в %s проходит %s. Выберите, пожалуйста, один из этих дней.".formatted(venueName(offer), daysText(offer));
            case OUTSIDE_LUNCH_HOURS -> "Бизнес-ланч подают с %s до %s. Выберите, пожалуйста, время в этом промежутке.".formatted(offer.from().format(TIME_TEXT), offer.to().format(TIME_TEXT));
            case ALREADY_PASSED -> "Это время уже прошло. Выберите, пожалуйста, более позднее.";
        };
    }

    static String daysText(BusinessLunchOffer offer) {
        if (offer.days().size() == WEEKDAYS.size() && offer.days().containsAll(WEEKDAYS)) {
            return "по будням";
        }
        return "в дни: " + String.join(", ", offer.days().stream().sorted().map(day -> day.getDisplayName(TextStyle.SHORT, RU)).toList());
    }

    static String hoursText(BusinessLunchOffer offer) {
        return "с %s до %s".formatted(offer.from().format(TIME_TEXT), offer.to().format(TIME_TEXT));
    }

    static String venueName(BusinessLunchOffer offer) {
        return offer.venueName() == null || offer.venueName().isBlank() ? offer.venueCode() : offer.venueName();
    }

    static List<List<String>> rows(List<String> labels, int columns) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i += columns) {
            rows.add(List.copyOf(labels.subList(i, Math.min(i + columns, labels.size()))));
        }
        return rows;
    }

    private static String capitalize(String value) {
        return value == null || value.isBlank() ? "" : value.substring(0, 1).toUpperCase(RU) + value.substring(1);
    }
}
