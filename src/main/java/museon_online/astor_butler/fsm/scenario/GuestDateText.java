package museon_online.astor_butler.fsm.scenario;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a day or a time out of what a guest typed. A guest may type anything, so nothing here throws:
 * "32.13" is simply not a date, and "19.30" is the time half past seven, not the nineteenth of a thirtieth month.
 */
final class GuestDateText {
    private static final Pattern DAY_MONTH = Pattern.compile("\\b(\\d{1,2})[./-](\\d{1,2})(?:[./-](\\d{2,4}))?\\b");
    private static final Pattern DOTTED_TIME = Pattern.compile("(?<![\\d./-])([01]?\\d|2[0-3])\\.([0-5]\\d)(?![\\d./-])");

    private GuestDateText() {
    }

    /** The first day.month or day.month.year in the text that exists in the calendar. A day already past this year means next year. */
    static Optional<LocalDate> dayMonth(String text, LocalDate today) {
        Matcher matcher = DAY_MONTH.matcher(text);
        while (matcher.find()) {
            int day = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            boolean yearGiven = matcher.group(3) != null;
            int year = yearGiven ? fullYear(matcher.group(3)) : today.getYear();
            if (!exists(year, month, day)) {
                continue;
            }
            LocalDate parsed = LocalDate.of(year, month, day);
            return Optional.of(!yearGiven && parsed.isBefore(today) ? parsed.plusYears(1) : parsed);
        }
        return Optional.empty();
    }

    /**
     * A time written with a dot, such as "19.30" or "20.00". Taken as a time only when the same digits cannot be
     * a day of this year: "15.10" stays the fifteenth of October.
     */
    static Optional<LocalTime> dottedTime(String text, LocalDate today) {
        Matcher matcher = DOTTED_TIME.matcher(text);
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            if (!exists(today.getYear(), minute, hour)) {
                return Optional.of(LocalTime.of(hour, minute));
            }
        }
        return Optional.empty();
    }

    /** A yyyy-mm-dd date, when that day exists. */
    static Optional<LocalDate> isoDate(String text) {
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static boolean exists(int year, int month, int day) {
        return month >= 1 && month <= 12 && day >= 1 && day <= YearMonth.of(year, month).lengthOfMonth();
    }

    private static int fullYear(String value) {
        int year = Integer.parseInt(value);
        return year < 100 ? 2000 + year : year;
    }
}
